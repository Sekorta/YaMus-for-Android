package ru.yamus.app.core.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import ru.yamus.app.core.download.OfflineEntry
import ru.yamus.app.core.download.OfflineLibrary
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.artistText
import ru.yamus.app.core.util.displayTitle
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Единый плеер приложения. Работает поверх MediaPlayer и локального
 * расшифровывающего прокси (см. [StreamProxy]).
 */
object PlaybackManager {

    const val REPEAT_OFF = 0
    const val REPEAT_ALL = 1
    const val REPEAT_ONE = 2

    interface Listener {
        fun onPlaybackChanged()
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    private val queue = ArrayList<Track>()

    private var appContext: Context? = null
    private var audioManager: AudioManager? = null
    private var mediaPlayer: MediaPlayer? = null

    @Volatile
    var currentTrack: Track? = null
        private set

    @Volatile
    var isPlaying: Boolean = false
        private set

    @Volatile
    var isPreparing: Boolean = false
        private set

    @Volatile
    var shuffle: Boolean = false
        private set

    @Volatile
    var repeatMode: Int = REPEAT_OFF
        private set

    @Volatile
    var waveActive: Boolean = false
        private set

    var onWaveExhausted: (() -> Unit)? = null

    /** Уведомления для «Моей волны»: старт, дослушан до конца, пропуск. */
    var onWaveTrackStarted: ((Track) -> Unit)? = null
    var onWaveTrackFinished: ((Track, Int) -> Unit)? = null
    var onWaveTrackSkipped: ((Track, Int) -> Unit)? = null

    private var playToken = 0
    private var feedbackStarted = false
    private var prepared = false
    private var pendingSeek = -1
    private var errorCount = 0
    private var prepareRetries = 0
    private var completionRetries = 0
    private var playStartedAt = 0L
    private var localFallbackTried = false
    private var httpStreamingBroken = false
    private var localStream: java.io.FileInputStream? = null
    private var focusRequested = false

    private var prefetchedTrack: Track? = null
    private var prefetchToken = 0
    private var forcedNextTrack: Track? = null
    private var pendingWavePlay = false
    private var waveLoadRequested = false
    private var waveEmptyBatches = 0
    private var queueTag: String? = null

    /** Поток, из которого сейчас играет трек: нужен для автоскачивания. */
    @Volatile
    private var currentSource: StreamProxy.Source? = null

    /**
     * Позиция играющего трека в очереди. Раньше индекс искался по id, но
     * станция может прислать уже звучавший трек повторно — тогда поиск
     * находил первое вхождение и плеер возвращался назад по очереди
     * (волна «зацикливалась» на нескольких треках). Храним индекс явно.
     */
    private var queueIndex = -1

    private val mainHandler = Handler(Looper.getMainLooper())

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS,
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (isPlaying) pause()
        }
    }

    private val noisyReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == AudioManager.ACTION_AUDIO_BECOMING_NOISY) {
                pause()
            }
        }
    }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        StreamProxy.start()
        try {
            ContextCompat.registerReceiver(
                context,
                noisyReceiver,
                IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY),
                ContextCompat.RECEIVER_EXPORTED
            )
        } catch (_: Exception) {
        }
    }

    fun addListener(listener: Listener) {
        listeners.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() {
        for (listener in listeners) {
            try {
                listener.onPlaybackChanged()
            } catch (_: Exception) {
            }
        }
    }

    fun queueSize(): Int = queue.size

    /** Id треков текущей очереди — для фильтрации повторов волны. */
    fun waveTrackIds(): List<String> = queue.map { it.id }

    fun currentIndex(): Int {
        val track = currentTrack ?: return -1
        if (queueIndex in queue.indices && queue[queueIndex].id == track.id) return queueIndex
        // Страховка на случай, если очередь заменили в обход playAt().
        val found = queue.indexOfFirst { it.id == track.id }
        if (found >= 0) queueIndex = found
        return found
    }

    fun playQueue(
        tracks: List<Track>,
        startIndex: Int,
        wave: Boolean = false,
        tag: String? = null
    ) {
        val playable = tracks.filter { it.id.isNotBlank() }
        if (playable.isEmpty()) return
        queue.clear()
        queue.addAll(playable)
        queueIndex = -1
        queueTag = tag
        val index = startIndex.coerceIn(0, playable.size - 1)
        waveActive = wave
        // Для волны порядок треков задаёт станция, перемешивание недоступно.
        if (wave) shuffle = false
        errorCount = 0
        prefetchedTrack = null
        prefetchToken++
        forcedNextTrack = null
        pendingWavePlay = false
        waveLoadRequested = false
        waveEmptyBatches = 0
        playAt(index)
    }

    fun toggle() {
        if (currentTrack == null) return
        if (isPlaying) {
            pause()
        } else {
            if (prepared) {
                start()
            } else {
                playAt(currentIndex().coerceAtLeast(0))
            }
        }
    }

    fun pause() {
        try {
            mediaPlayer?.let { if (it.isPlaying) it.pause() }
        } catch (_: Exception) {
        }
        isPlaying = false
        abandonFocus()
        notifyChanged()
    }

    fun start() {
        try {
            mediaPlayer?.let {
                if (prepared && !it.isPlaying) {
                    it.start()
                    isPlaying = true
                    requestFocus()
                    notifyChanged()
                }
            }
        } catch (_: Exception) {
        }
    }

    fun next() {
        notifyWaveSkipped()
        advance(false)
    }

    fun previous() {
        if (queue.isEmpty()) return
        if (position() > 3000) {
            seekTo(0)
            return
        }
        val index = currentIndex()
        if (index > 0) {
            notifyWaveSkipped()
            playAt(index - 1)
        } else if (repeatMode == REPEAT_ALL && !waveActive) {
            playAt(queue.size - 1)
        } else {
            seekTo(0)
        }
    }

    fun seekTo(ms: Int) {
        if (prepared) {
            try {
                mediaPlayer?.seekTo(ms)
            } catch (_: Exception) {
            }
        } else {
            pendingSeek = ms
        }
        notifyChanged()
    }

    fun setShuffle(enabled: Boolean) {
        if (waveActive) return
        shuffle = enabled
        notifyChanged()
        schedulePrefetch()
    }

    fun toggleShuffle() {
        if (waveActive) {
            toast("В «Моей волне» порядок треков задаёт станция")
            return
        }
        shuffle = !shuffle
        notifyChanged()
        schedulePrefetch()
    }

    fun toggleRepeat() {
        repeatMode = (repeatMode + 1) % 3
        notifyChanged()
        schedulePrefetch()
    }

    fun playNext(track: Track) {
        if (track.id.isBlank()) return
        if (queue.isEmpty() || currentTrack == null) {
            playQueue(listOf(track), 0)
            return
        }
        val index = currentIndex()
        val insertAt = (if (index < 0) 0 else index + 1).coerceIn(0, queue.size)
        queue.add(insertAt, track)
        // Текущий трек остаётся тем же, но его позиция сдвигается.
        if (index >= 0 && insertAt <= index) queueIndex = index + 1
        forcedNextTrack = track
        notifyChanged()
        schedulePrefetch()
    }

    fun appendWave(tracks: List<Track>) {
        // Станция охотно повторяет уже игравшие треки. Без фильтра очередь
        // разрасталась дублями, а вторая порция почти целиком совпадала с
        // первой — волна выглядела как цикл из нескольких песен. Сверяемся
        // с последними треками, а не со всей историей: радио всё же может
        // повторить песню спустя много времени.
        val known = HashSet<String>(80)
        val from = (queue.size - 40).coerceAtLeast(0)
        for (i in from until queue.size) known.add(queue[i].id)
        val playable = ArrayList<Track>(tracks.size)
        for (item in tracks) {
            if (item.id.isBlank()) continue
            if (!known.add(item.id)) continue
            playable.add(item)
        }

        waveLoadRequested = false
        if (playable.isEmpty()) {
            // Одна и та же порция повторов — просим станцию ещё раз, но не
            // бесконечно: после нескольких пустых ответов волну останавливаем.
            if (waveEmptyBatches < 3) {
                waveEmptyBatches++
                mainHandler.postDelayed({ maybeLoadWave() }, 1000L * waveEmptyBatches)
            } else {
                waveEmptyBatches = 0
                stop()
            }
            return
        }
        waveEmptyBatches = 0

        // Считать «доиграли ли до конца» нужно ДО добавления: иначе после
        // addAll текущий трек уже не последний и отложенный переход теряется.
        val wasExhausted = currentIndex() >= queue.size - 1
        val firstNew = queue.size
        queue.addAll(playable)
        waveLoadRequested = false
        if (wasExhausted && pendingWavePlay) {
            pendingWavePlay = false
            playAt(firstNew)
        } else {
            notifyChanged()
            schedulePrefetch()
        }
    }

    /**
     * Обновляет очередь, которая уже играет с этого экрана. Нужно, когда
     * список сначала показал первые 200 треков, а потом догрузил остальные:
     * пользователь мог начать слушать, не дожидаясь конца загрузки.
     */
    fun replaceQueue(tag: String, tracks: List<Track>) {
        if (queueTag != tag) return
        val current = currentTrack ?: return
        val playable = tracks.filter { it.id.isNotBlank() }
        if (playable.none { it.id == current.id }) return
        queue.clear()
        queue.addAll(playable)
        queueIndex = queue.indexOfFirst { it.id == current.id }
        prefetchedTrack = null
        prefetchToken++
        // «Играть следующим» сохраняем, если трек есть в полном списке.
        val forced = forcedNextTrack
        if (forced != null && playable.none { it.id == forced.id }) {
            forcedNextTrack = null
        }
        notifyChanged()
        schedulePrefetch()
    }

    fun stop() {
        playToken++
        prefetchToken++
        waveActive = false
        onWaveExhausted = null
        queueTag = null
        queue.clear()
        queueIndex = -1
        currentTrack = null
        isPlaying = false
        isPreparing = false
        prepared = false
        pendingSeek = -1
        prefetchedTrack = null
        forcedNextTrack = null
        pendingWavePlay = false
        waveLoadRequested = false
        waveEmptyBatches = 0
        releasePlayer()
        abandonFocus()
        notifyChanged()
    }

    fun stopWave() {
        if (waveActive) stop()
    }

    fun position(): Int {
        return try {
            if (prepared) mediaPlayer?.currentPosition ?: 0 else 0
        } catch (_: Exception) {
            0
        }
    }

    fun duration(): Int {
        return try {
            if (prepared) mediaPlayer?.duration?.takeIf { it > 0 } ?: 0 else 0
        } catch (_: Exception) {
            0
        }
    }

    private fun advance(auto: Boolean) {
        if (queue.isEmpty()) return
        val index = currentIndex()

        // «Играть следующим» важнее перемешивания и повтора.
        val forced = forcedNextTrack
        if (forced != null) {
            // Ищем вставленный трек после текущего: в волне тот же id мог
            // встречаться раньше, и первое вхождение вернуло бы плеер назад.
            var forcedIndex = -1
            if (index >= 0) {
                for (i in index + 1 until queue.size) {
                    if (queue[i].id == forced.id) {
                        forcedIndex = i
                        break
                    }
                }
            }
            if (forcedIndex < 0) forcedIndex = queue.indexOfFirst { it.id == forced.id }
            if (forcedIndex >= 0 && forcedIndex != index) {
                forcedNextTrack = null
                playAt(forcedIndex)
                return
            }
            forcedNextTrack = null
        }

        if (auto && repeatMode == REPEAT_ONE) {
            playAt(index.coerceAtLeast(0))
            return
        }
        if (shuffle && !waveActive && queue.size > 1) {
            playAt(nextShuffleIndex())
            return
        }
        if (index >= 0 && index + 1 < queue.size) {
            playAt(index + 1)
            return
        }
        // Волна бесконечная: повтор всех треков не должен зацикливать уже
        // загруженную порцию — вместо этого просим у станции следующую.
        if (waveActive && onWaveExhausted != null) {
            pendingWavePlay = true
            isPreparing = true
            isPlaying = false
            notifyChanged()
            onWaveExhausted?.invoke()
            return
        }
        if (repeatMode == REPEAT_ALL) {
            playAt(0)
            return
        }
        isPlaying = false
        isPreparing = false
        seekTo(0)
        notifyChanged()
    }

    /** Следующий трек при перемешивании: тот же, что уже предзагружен. */
    private fun nextShuffleIndex(): Int {
        val current = currentIndex()
        val existing = prefetchedTrack
        if (existing != null) {
            val index = queue.indexOfFirst { it === existing }
            if (index >= 0 && index != current) return index
        }
        var next = (Math.random() * queue.size).toInt()
        if (next == current) next = (next + 1) % queue.size
        return next
    }

    /** Следующий трек без изменения очереди — для предзагрузки. */
    private fun peekNextTrack(): Track? {
        if (queue.isEmpty()) return null

        val forced = forcedNextTrack
        if (forced != null && forced.id != currentTrack?.id && queue.any { it.id == forced.id }) {
            return forced
        }
        if (repeatMode == REPEAT_ONE) return null

        val current = currentIndex()
        if (shuffle && !waveActive && queue.size > 1) {
            val existing = prefetchedTrack
            if (existing != null &&
                existing.id != currentTrack?.id &&
                queue.any { it.id == existing.id }
            ) {
                return existing
            }
            var next = (Math.random() * queue.size).toInt()
            if (next == current) next = (next + 1) % queue.size
            return queue[next]
        }

        if (current < 0) return queue.firstOrNull()
        if (current + 1 < queue.size) return queue[current + 1]
        // В волне «следующий» всегда новый трек от станции, а не первый
        // из уже сыгранного списка.
        if (repeatMode == REPEAT_ALL && !waveActive) {
            val first = queue.firstOrNull()
            return if (first?.id == currentTrack?.id) null else first
        }
        return null
    }

    /**
     * Предзагружает следующий трек в кэш. Для волны заранее просит новую
     * порцию, чтобы к концу текущего трека уже была очередь.
     */
    private fun schedulePrefetch() {
        val token = ++prefetchToken
        // Волна догружается заранее — когда до конца очереди остаётся один
        // трек, а не после его окончания. Иначе между порциями слышна пауза.
        maybeLoadWave()
        val next = peekNextTrack()
        prefetchedTrack = next
        if (next == null) return
        val context = appContext ?: return
        AppScope.launch {
            if (token != prefetchToken) return@launch
            // Скачанное в библиотеку уже лежит на диске: сеть не нужна.
            if (OfflineLibrary.find(next) != null) return@launch
            if (TrackDownloader.cachedFile(context, next.id) != null) return@launch
            val source = TrackUrlResolver.resolve(next)
            if (source == null || token != prefetchToken) return@launch
            TrackDownloader.download(context, next.id, source)
        }
    }

    private fun maybeLoadWave() {
        if (!waveActive || waveLoadRequested) return
        // Оставляем запас в один трек на время запроса к станции.
        if (currentIndex() < queue.size - 2) return
        val callback = onWaveExhausted ?: return
        waveLoadRequested = true
        callback()
    }

    private fun playAt(index: Int) {
        val track = queue.getOrNull(index) ?: return
        queueIndex = index
        currentTrack = track
        isPreparing = true
        isPlaying = false
        prepared = false
        pendingSeek = -1
        localFallbackTried = false
        prefetchedTrack = null
        prefetchToken++
        forcedNextTrack = null
        pendingWavePlay = false
        waveLoadRequested = false
        feedbackStarted = false
        prepareRetries = 0
        completionRetries = 0
        playStartedAt = 0L
        currentSource = null
        notifyChanged()
        prepareTrack(track, local = false)
    }

    /**
     * Готовит MediaPlayer для трека.
     *
     * [local] = false — обычный HTTP-поток через [StreamProxy]. Если прошивка
     * не умеет играть HTTP (media server падает в HTTPBase::Create), трек
     * скачивается в кэш и играется из файла.
     */
    private fun prepareTrack(track: Track, local: Boolean) {
        val token = ++playToken
        releasePlayer()
        var sourceIsLocal = local || httpStreamingBroken
        var fromLibrary = false
        var libraryRetried = false
        currentSource = null

        val player = MediaPlayer()
        mediaPlayer = player
        try {
            @Suppress("DEPRECATION")
            player.setAudioStreamType(AudioManager.STREAM_MUSIC)
        } catch (_: Exception) {
        }

        player.setOnPreparedListener { mp ->
            if (token != playToken) return@setOnPreparedListener
            prepared = true
            isPreparing = false
            isPlaying = true
            errorCount = 0
            prepareRetries = 0
            playStartedAt = SystemClock.elapsedRealtime()
            if (pendingSeek > 0) {
                try {
                    mp.seekTo(pendingSeek)
                } catch (_: Exception) {
                }
                pendingSeek = -1
            }
            try {
                mp.start()
            } catch (_: Exception) {
            }
            requestFocus()
            notifyChanged()
            notifyWaveStarted(track)
            // Автоскачивание срабатывает именно здесь: пользователь реально
            // начал слушать трек, а не просто пролистал список.
            appContext?.let {
                OfflineLibrary.autoDownload(
                    it,
                    track,
                    currentSource,
                    fromLikes = queueTag.orEmpty().startsWith("liked")
                )
            }
            schedulePrefetch()
        }
        player.setOnCompletionListener {
            if (token != playToken) return@setOnCompletionListener
            // Обрыв HTTP-потока MediaPlayer иногда показывает как обычное
            // окончание трека. Отличаем его от настоящего конца по позиции
            // и реально прошедшему времени: иначе плеер «сам» перескакивал
            // на следующий трек. Один раз пробуем перезапустить тот же трек.
            val total = duration()
            val position = position()
            val elapsed = if (playStartedAt > 0) {
                SystemClock.elapsedRealtime() - playStartedAt
            } else {
                0L
            }
            val truncated = total > 15_000 &&
                position < total - 3_000 &&
                elapsed < total - 5_000
            if (truncated && completionRetries < 1) {
                completionRetries++
                isPlaying = false
                isPreparing = true
                prepared = false
                notifyChanged()
                mainHandler.postDelayed({
                    if (token == playToken) prepareTrack(track, local = false)
                }, 400)
                return@setOnCompletionListener
            }
            isPlaying = false
            notifyWaveFinished(track)
            advance(true)
        }
        player.setOnErrorListener { _, what, _ ->
            if (token != playToken) return@setOnErrorListener true
            isPreparing = false
            isPlaying = false
            prepared = false
            notifyChanged()
            if (fromLibrary && !libraryRetried) {
                // Файл в библиотеке повреждён или удалён вручную: убираем
                // запись из индекса и пробуем обычный поток из сети.
                libraryRetried = true
                fromLibrary = false
                appContext?.let { OfflineLibrary.forget(it, track) }
                isPreparing = true
                notifyChanged()
                mainHandler.postDelayed({
                    if (token == playToken) prepareTrack(track, local = false)
                }, 500)
                return@setOnErrorListener true
            }
            if (!sourceIsLocal && !localFallbackTried) {
                // 100 = MEDIA_ERROR_SERVER_DIED: прошивка уронила media server
                // на HTTP-потоке, дальше в этой сессии сразу идём в локальный файл.
                if (what == 100) httpStreamingBroken = true
                localFallbackTried = true
                isPreparing = true
                notifyChanged()
                // Даём media server время подняться после падения.
                mainHandler.postDelayed({
                    if (token == playToken) prepareTrack(track, local = true)
                }, 700)
                return@setOnErrorListener true
            }
            if (sourceIsLocal) {
                appContext?.let { TrackDownloader.invalidate(it, track.id) }
            }
            onPrepareFailed(token)
            true
        }

        AppScope.launch {
            val context = appContext
            // 1. Скачанное в «YaMus Library» играем прямо с диска: это
            // работает без сети и отдаёт ровно тот файл, который сохранили.
            if (!local && context != null) {
                val entry = OfflineLibrary.find(track)
                if (entry != null) {
                    if (token != playToken) return@launch
                    if (playOffline(player, context, entry, token)) {
                        sourceIsLocal = true
                        fromLibrary = true
                        return@launch
                    }
                    OfflineLibrary.forget(context, track)
                }
            }

            // 2. Уже предзагруженный трек играем из приватного кэша.
            val cached = if (!local && context != null) {
                TrackDownloader.cachedFile(context, track.id)
            } else {
                null
            }
            if (cached != null) {
                if (token != playToken) return@launch
                sourceIsLocal = true
                playFile(player, cached, token)
                return@launch
            }

            val source = TrackUrlResolver.resolve(track)
            if (token != playToken) return@launch
            if (source == null) {
                onPrepareFailed(token)
                return@launch
            }
            currentSource = source
            try {
                if (local || httpStreamingBroken) {
                    val file = context?.let {
                        TrackDownloader.download(it, track.id, source)
                    }
                    if (token != playToken) return@launch
                    if (file == null) {
                        onPrepareFailed(token)
                        return@launch
                    }
                    sourceIsLocal = true
                    playFile(player, file, token)
                } else {
                    android.util.Log.d(
                        "YaMusProxy",
                        "source ${java.net.URL(source.url).host} ${source.contentType}"
                    )
                    player.setDataSource(StreamProxy.setSource(source))
                    player.prepareAsync()
                }
            } catch (_: Exception) {
                onPrepareFailed(token)
            }
        }
    }

    /** Проигрывает файл из пользовательской библиотеки. */
    private fun playOffline(
        player: MediaPlayer,
        context: Context,
        entry: OfflineEntry,
        token: Int
    ): Boolean {
        if (token != playToken) return false
        return try {
            val uri = entry.uri
            if (!uri.isNullOrBlank()) {
                player.setDataSource(context, Uri.parse(uri))
                player.prepareAsync()
                true
            } else {
                val path = entry.path ?: return false
                val stream = FileInputStream(path)
                localStream = stream
                player.setDataSource(stream.fd)
                player.prepareAsync()
                true
            }
        } catch (_: Exception) {
            try {
                localStream?.close()
            } catch (_: Exception) {
            }
            localStream = null
            false
        }
    }

    private fun notifyWaveStarted(track: Track) {
        if (!waveActive || feedbackStarted) return
        val callback = onWaveTrackStarted ?: return
        feedbackStarted = true
        callback(track)
    }

    private fun notifyWaveFinished(track: Track) {
        if (!waveActive) return
        val callback = onWaveTrackFinished ?: return
        val played = duration().coerceAtLeast(position()) / 1000
        callback(track, played)
    }

    private fun notifyWaveSkipped() {
        if (!waveActive || !prepared) return
        val track = currentTrack ?: return
        val callback = onWaveTrackSkipped ?: return
        callback(track, position() / 1000)
    }

    private fun playFile(player: MediaPlayer, file: File, token: Int) {
        if (token != playToken) return
        try {
            // Медиасервер не имеет доступа к приватному кэшу приложения,
            // поэтому отдаём ему уже открытый файловый дескриптор.
            val stream = FileInputStream(file)
            localStream = stream
            player.setDataSource(stream.fd)
            player.prepareAsync()
        } catch (_: Exception) {
            onPrepareFailed(token)
        }
    }

    private fun onPrepareFailed(token: Int) {
        if (token != playToken) return
        isPreparing = false
        isPlaying = false
        prepared = false
        // Разовый сбой сети или ответа API не должен означать «пропустить
        // трек»: сначала пробуем тот же трек ещё пару раз.
        val track = currentTrack
        if (track != null && prepareRetries < 2) {
            prepareRetries++
            isPreparing = true
            notifyChanged()
            mainHandler.postDelayed({
                if (token == playToken) prepareTrack(track, local = false)
            }, 500L * prepareRetries)
            return
        }
        prepareRetries = 0
        notifyChanged()
        errorCount++
        if (errorCount <= 5) {
            advance(true)
        } else {
            errorCount = 0
            stop()
        }
    }

    private fun releasePlayer() {
        val player = mediaPlayer
        mediaPlayer = null
        prepared = false
        try {
            player?.setOnPreparedListener(null)
            player?.setOnCompletionListener(null)
            player?.setOnErrorListener(null)
            player?.reset()
            player?.release()
        } catch (_: Exception) {
        }
        try {
            localStream?.close()
        } catch (_: Exception) {
        }
        localStream = null
    }

    private fun requestFocus() {
        if (focusRequested) return
        try {
            @Suppress("DEPRECATION")
            val result = audioManager?.requestAudioFocus(
                focusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            )
            focusRequested = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (_: Exception) {
        }
    }

    private fun abandonFocus() {
        if (!focusRequested) return
        try {
            @Suppress("DEPRECATION")
            audioManager?.abandonAudioFocus(focusListener)
        } catch (_: Exception) {
        }
        focusRequested = false
    }

    fun currentDescription(): String {
        val track = currentTrack ?: return ""
        return "${track.displayTitle()} — ${track.artistText()}"
    }

    fun refresh() {
        notifyChanged()
    }

    fun toast(message: String) {
        val context = appContext ?: return
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
    }
}
