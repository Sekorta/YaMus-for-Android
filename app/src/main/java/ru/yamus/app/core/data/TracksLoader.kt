package ru.yamus.app.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.models.Track

object TracksLoader {
    private const val BATCH = 200
    private const val CONCURRENCY = 4

    /**
     * Загружает треки и возвращает их в порядке [ids]. API не гарантирует
     * порядок ответа, а для «Мне нравится» важен порядок добавления.
     *
     * Первый пакет загружается сразу, остальные — параллельно. Поэтому
     * плейлист на 2000 треков показывает первые строки без ожидания всех
     * десяти запросов.
     */
    suspend fun loadByIds(
        ids: List<String>,
        onUpdate: ((List<Track>) -> Unit)? = null
    ): List<Track> = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext emptyList()

        val byId = HashMap<String, Track>(ids.size)
        val lock = Any()

        suspend fun consume(chunk: List<String>) {
            val tracks = fetch(chunk)
            val snapshot = synchronized(lock) {
                tracks.forEach { track ->
                    if (track.id.isNotBlank()) byId[track.id] = track
                }
                ids.mapNotNull { byId[it] }
            }
            if (onUpdate != null && snapshot.isNotEmpty()) {
                withContext(Dispatchers.Main) { onUpdate(snapshot) }
            }
        }

        val first = ids.take(BATCH)
        consume(first)

        val rest = ids.drop(BATCH)
        if (rest.isNotEmpty()) {
            val semaphore = Semaphore(CONCURRENCY)
            coroutineScope {
                rest.chunked(BATCH).map { chunk ->
                    async {
                        semaphore.withPermit { consume(chunk) }
                    }
                }.awaitAll()
            }
        }

        ids.mapNotNull { byId[it] }
    }

    private suspend fun fetch(chunk: List<String>): List<Track> {
        return try {
            ApiClient.api.getTracks(chunk.joinToString(",")).result.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }
}
