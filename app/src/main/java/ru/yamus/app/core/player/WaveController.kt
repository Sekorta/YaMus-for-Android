package ru.yamus.app.core.player

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.data.WaveHistoryStore
import ru.yamus.app.core.models.RotorFeedbackEvent
import ru.yamus.app.core.models.RotorFeedbackRequest
import ru.yamus.app.core.models.RotorItem
import ru.yamus.app.core.models.RotorSessionRequest
import ru.yamus.app.core.models.RotorSessionTracksRequest
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.isPlayable
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * «Моя волна»: бесконечная персональная станция user:onyourwave.
 *
 * Использует новый API роторных сессий. В отличие от старого
 * `GET /rotor/station/{station}/tracks`, сессия принимает очередь уже
 * прослушанного и не повторяет эти треки в следующих порциях. Поэтому
 * каждый запуск волны звучит по-новому, а не крутит один и тот же пул.
 *
 * История прослушанного хранится на диске ([WaveHistoryStore]) и передаётся
 * станции при создании сессии — даже после перезапуска приложения волна
 * не начинается со старых треков.
 *
 * Первая порция запускает очередь, каждая следующая догружается заранее —
 * когда до конца очереди остаётся один трек. Если сессии недоступны,
 * остаётся резервный путь через старый GET-эндпоинт.
 *
 * Также поддерживается волна по конкретному треку — станция `track:{id}`.
 */
object WaveController {

    private const val DEFAULT_STATION = "user:onyourwave"
    private const val TRACK_STATION_PREFIX = "track:"
    private const val MAX_SESSION_QUEUE = 200

    @Volatile
    private var loading = false

    @Volatile
    var station: String = DEFAULT_STATION
        private set

    @Volatile
    var seedTrack: Track? = null
        private set

    private var requestToken = 0

    private val sessionLock = Any()
    private val seenIds = LinkedHashSet<String>()
    private val excludedIds = HashSet<String>()
    private val batchByTrack = ConcurrentHashMap<String, String>()

    @Volatile
    private var sessionId: String? = null

    @Volatile
    private var sessionBatchId: String? = null

    /** Одна порция, полученная от станции. */
    private class BatchChunk(
        val batchId: String?,
        val items: List<RotorItem>
    )

    /** Результат создания новой сессии (готовится в IO-потоке). */
    private class FirstBatch(
        val sessionId: String?,
        val history: List<String>,
        val chunks: List<BatchChunk>,
        val tracks: List<Track>
    )

    /** Результат запроса следующей порции. */
    private class NextBatch(
        val batchId: String?,
        val items: List<RotorItem>,
        val tracks: List<Track>
    )

    val isActive: Boolean
        get() = PlaybackManager.waveActive

    val isLoading: Boolean
        get() = loading

    val isTrackRadio: Boolean
        get() = seedTrack != null && PlaybackManager.waveActive

    fun toggle() {
        if (isActive) stop() else start()
    }

    /** Обычная «Моя волна». */
    fun start() {
        if (loading) return
        if (PlaybackManager.waveActive &&
            PlaybackManager.currentTrack != null &&
            seedTrack == null
        ) {
            return
        }

        station = DEFAULT_STATION
        seedTrack = null
        val token = ++requestToken
        val queuedIds = PlaybackManager.waveTrackIds().toHashSet()
        loading = true
        if (PlaybackManager.waveActive) PlaybackManager.stopWave()
        PlaybackManager.onWaveExhausted = { loadMore() }
        installFeedbackCallbacks()

        AppScope.launch {
            val data = firstBatch(DEFAULT_STATION, queuedIds)
            if (token != requestToken) return@launch
            loading = false
            if (data.tracks.isEmpty()) {
                PlaybackManager.toast("Не удалось включить волну")
                resetState()
                PlaybackManager.refresh()
                return@launch
            }
            applyFirstBatch(data)
            PlaybackManager.playQueue(data.tracks, 0, wave = true)
        }
    }

    /** Возврат с волны по треку к обычной волне. */
    fun startNormal() {
        if (PlaybackManager.waveActive || seedTrack != null) {
            requestToken++
            loading = false
            station = DEFAULT_STATION
            seedTrack = null
            clearSessionState()
            PlaybackManager.onWaveExhausted = null
            PlaybackManager.stopWave()
        }
        start()
    }

    /** Волна по конкретному треку (станция track:{id}). */
    fun startTrackRadio(track: Track) {
        if (track.id.isBlank()) return
        val targetStation = TRACK_STATION_PREFIX + track.id
        val token = ++requestToken
        val queuedIds = PlaybackManager.waveTrackIds().toHashSet()
        station = targetStation
        seedTrack = track
        loading = true
        PlaybackManager.stop()
        PlaybackManager.onWaveExhausted = { loadMore() }
        installFeedbackCallbacks()

        AppScope.launch {
            val data = firstBatch(targetStation, queuedIds)
            if (token != requestToken) return@launch
            loading = false
            if (data.tracks.isEmpty()) {
                PlaybackManager.toast("По этому треку не удалось собрать волну")
                resetState()
                PlaybackManager.refresh()
                return@launch
            }
            applyFirstBatch(data)
            PlaybackManager.playQueue(data.tracks, 0, wave = true)
        }
    }

    fun stop() {
        requestToken++
        loading = false
        resetState()
        PlaybackManager.stopWave()
    }

    private fun resetState() {
        station = DEFAULT_STATION
        seedTrack = null
        clearSessionState()
        PlaybackManager.onWaveExhausted = null
        PlaybackManager.onWaveTrackStarted = null
        PlaybackManager.onWaveTrackFinished = null
        PlaybackManager.onWaveTrackSkipped = null
    }

    private fun installFeedbackCallbacks() {
        PlaybackManager.onWaveTrackStarted = { track -> onTrackStarted(track) }
        PlaybackManager.onWaveTrackFinished = { track, seconds ->
            sendFeedback("trackFinished", track, seconds)
        }
        PlaybackManager.onWaveTrackSkipped = { track, seconds ->
            sendFeedback("skip", track, seconds)
        }
    }

    private fun onTrackStarted(track: Track) {
        // Запоминаем только реально начавшиеся треки: если приложение
        // закрылось до старта, трек не должен выпадать из будущих волн.
        WaveHistoryStore.remember(track.id)
        sendFeedback("trackStarted", track, null)
    }

    private fun sendFeedback(type: String, track: Track, playedSeconds: Int?) {
        if (!PlaybackManager.waveActive) return
        val sid = sessionId ?: return
        val batchId = batchByTrack[track.id]
        val event = RotorFeedbackEvent(
            type = type,
            timestamp = isoNow(),
            trackId = track.id,
            totalPlayedSeconds = playedSeconds
        )
        AppScope.launch {
            withContext(Dispatchers.IO) {
                try {
                    ApiClient.api.sendRotorSessionFeedback(
                        sid,
                        RotorFeedbackRequest(event = event, batchId = batchId)
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun loadMore() {
        if (loading) return
        val token = requestToken
        val sid = sessionId
        val queue = sessionQueue()
        val excluded = synchronized(sessionLock) { HashSet(excludedIds) }
        val queuedIds = PlaybackManager.waveTrackIds().toHashSet()
        loading = true
        AppScope.launch {
            val data = nextBatch(sid, queue, excluded, queuedIds)
            if (token != requestToken) return@launch
            loading = false
            if (data == null || data.tracks.isEmpty()) {
                // appendWave сам повторит запрос, а после нескольких пустых
                // порций остановит волну, чтобы не крутить тишину.
                if (data != null) commitNextBatch(data)
                PlaybackManager.appendWave(emptyList())
                return@launch
            }
            commitNextBatch(data)
            PlaybackManager.appendWave(data.tracks)
        }
    }

    /**
     * Создаёт сессию и возвращает первую порцию. Ничего не пишет в общее
     * состояние: оно применяется в [applyFirstBatch] уже на главном потоке
     * и только если запрос всё ещё актуален.
     */
    private suspend fun firstBatch(
        targetStation: String,
        queuedIds: Set<String>
    ): FirstBatch = withContext(Dispatchers.IO) {
        val history = WaveHistoryStore.ids()
        val seen = LinkedHashSet(history)
        val excluded = HashSet(history)

        fun addChunk(batchId: String?, items: List<RotorItem>) {
            for (item in items) {
                val id = item.track?.id ?: continue
                if (id.isBlank()) continue
                seen.add(id)
                excluded.add(id)
            }
        }

        val chunks = ArrayList<BatchChunk>(2)

        val session = try {
            ApiClient.api.createRotorSession(
                RotorSessionRequest(
                    seeds = listOf(targetStation),
                    queue = history.ifEmpty { null }
                )
            )
        } catch (_: Exception) {
            null
        }

        if (session != null &&
            !session.radioSessionId.isNullOrEmpty() &&
            !session.sequence.isNullOrEmpty()
        ) {
            val sid = session.radioSessionId
            val firstItems = session.sequence.orEmpty()
            var tracks = filterBatch(firstItems, excluded, queuedIds)
            chunks.add(BatchChunk(session.batchId, firstItems))
            addChunk(session.batchId, firstItems)

            if (tracks.isEmpty()) {
                val extra = requestSessionTracks(sid, seen.toList().takeLast(MAX_SESSION_QUEUE))
                if (extra != null) {
                    tracks = filterBatch(extra.second, excluded, queuedIds)
                    chunks.add(BatchChunk(extra.first, extra.second))
                    addChunk(extra.first, extra.second)
                }
            }
            return@withContext FirstBatch(sid, history, chunks, tracks)
        }

        // Резервный путь: старый эндпоинт станции. Повторы в нём отсекаем
        // локально по истории и по уже полученным в этой сессии трекам.
        val legacy = try {
            ApiClient.api.getRotorTracks(targetStation).result?.sequence.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
        val tracks = filterBatch(legacy, excluded, queuedIds)
        chunks.add(BatchChunk(null, legacy))
        addChunk(null, legacy)
        FirstBatch(null, history, chunks, tracks)
    }

    private suspend fun nextBatch(
        sid: String?,
        queue: List<String>,
        excluded: Set<String>,
        queuedIds: Set<String>
    ): NextBatch? = withContext(Dispatchers.IO) {
        if (sid == null) {
            val legacy = try {
                ApiClient.api.getRotorTracks(station).result?.sequence.orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            return@withContext NextBatch(
                null,
                legacy,
                filterBatch(legacy, excluded, queuedIds)
            )
        }

        val extra = requestSessionTracks(sid, queue) ?: return@withContext null
        NextBatch(
            extra.first,
            extra.second,
            filterBatch(extra.second, excluded, queuedIds)
        )
    }

    private suspend fun requestSessionTracks(
        sid: String,
        queue: List<String>
    ): Pair<String?, List<RotorItem>>? {
        return try {
            val response = ApiClient.api.getRotorSessionTracks(
                sid,
                RotorSessionTracksRequest(queue = queue)
            )
            response.batchId to response.sequence.orEmpty()
        } catch (_: Exception) {
            null
        }
    }

    /** Применяется на главном потоке после успешного создания сессии. */
    private fun applyFirstBatch(data: FirstBatch) {
        synchronized(sessionLock) {
            sessionId = data.sessionId
            sessionBatchId = null
            batchByTrack.clear()
            seenIds.clear()
            seenIds.addAll(data.history)
            excludedIds.clear()
            excludedIds.addAll(data.history)

            for (chunk in data.chunks) {
                val chunkBatch = chunk.batchId
                if (!chunkBatch.isNullOrEmpty()) sessionBatchId = chunkBatch
                val effectiveBatch = if (!chunkBatch.isNullOrEmpty()) chunkBatch else sessionBatchId
                for (item in chunk.items) {
                    val id = item.track?.id ?: continue
                    if (id.isBlank()) continue
                    seenIds.add(id)
                    excludedIds.add(id)
                    if (!effectiveBatch.isNullOrEmpty()) batchByTrack[id] = effectiveBatch
                }
            }
        }
    }

    /** Применяется на главном потоке после получения следующей порции. */
    private fun commitNextBatch(data: NextBatch) {
        synchronized(sessionLock) {
            val batchId = data.batchId
            if (!batchId.isNullOrEmpty()) sessionBatchId = batchId
            val effectiveBatch = if (!batchId.isNullOrEmpty()) batchId else sessionBatchId
            for (item in data.items) {
                val id = item.track?.id ?: continue
                if (id.isBlank()) continue
                seenIds.add(id)
                excludedIds.add(id)
                if (!effectiveBatch.isNullOrEmpty()) batchByTrack[id] = effectiveBatch
            }
        }
    }

    private fun sessionQueue(): List<String> = synchronized(sessionLock) {
        seenIds.toList().takeLast(MAX_SESSION_QUEUE)
    }

    private fun filterBatch(
        items: List<RotorItem>,
        excluded: Set<String>,
        queuedIds: Set<String>
    ): List<Track> {
        val localSeen = HashSet<String>(items.size)
        val result = ArrayList<Track>(items.size)
        for (item in items) {
            val type = item.type
            if (type != null && type != "track") continue
            val track = item.track ?: continue
            if (track.id.isBlank() || !track.isPlayable()) continue
            if (!localSeen.add(track.id)) continue
            if (track.id in excluded || track.id in queuedIds) continue
            result.add(track)
        }
        return result
    }

    private fun clearSessionState() {
        synchronized(sessionLock) {
            sessionId = null
            sessionBatchId = null
            batchByTrack.clear()
            seenIds.clear()
            excludedIds.clear()
        }
    }

    private fun isoNow(): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date())
    }
}
