package ru.yamus.app.core.data

import com.google.gson.JsonElement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.models.Album
import ru.yamus.app.core.models.Track
import java.util.concurrent.ConcurrentHashMap

/** Разбор неоднородных ответов artist-эндпоинтов и загрузка всех треков. */
object ArtistContent {

    private const val PAGE_SIZE = 100
    private const val CONCURRENCY = 4

    /** API отдаёт либо массив, либо объект { tracks: [...] }, элементы — трек или { track }. */
    fun parseTracks(element: JsonElement?): List<Track> {
        val array = when {
            element == null || element.isJsonNull -> null
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> element.asJsonObject.getAsJsonArray("tracks")
            else -> null
        } ?: return emptyList()

        val result = ArrayList<Track>(array.size())
        for (item in array) {
            if (!item.isJsonObject) continue
            val obj = item.asJsonObject
            val trackObject = obj.get("track")?.takeIf { it.isJsonObject }?.asJsonObject ?: obj
            val track = try {
                ApiClient.gson.fromJson(trackObject, Track::class.java)
            } catch (_: Exception) {
                null
            }
            if (track != null && track.id.isNotBlank()) result.add(track)
        }
        return result
    }

    /** direct-albums: массив или объект { albums: [...] }. */
    fun parseAlbums(element: JsonElement?): List<Album> {
        val array = when {
            element == null || element.isJsonNull -> null
            element.isJsonArray -> element.asJsonArray
            element.isJsonObject -> element.asJsonObject.getAsJsonArray("albums")
            else -> null
        } ?: return emptyList()

        val result = ArrayList<Album>(array.size())
        for (item in array) {
            if (!item.isJsonObject) continue
            val album = try {
                ApiClient.gson.fromJson(item, Album::class.java)
            } catch (_: Exception) {
                null
            }
            if (album != null && album.id > 0) result.add(album)
        }
        return result
    }

    /**
     * Загружает все треки исполнителя. Если количество известно, страницы
     * запрашиваются параллельно; иначе идём по страницам до пустой.
     */
    suspend fun loadAllTracks(
        artistId: Long,
        totalCount: Int,
        onUpdate: ((List<Track>) -> Unit)? = null
    ): List<Track> = withContext(Dispatchers.IO) {
        if (artistId <= 0) return@withContext emptyList()

        val first = fetchPage(artistId, 0)
        if (first.isEmpty()) return@withContext emptyList()

        // API может ограничивать pageSize сильнее, чем мы просим. Тогда
        // считаем число страниц по фактическому размеру первой страницы.
        val effectivePage = if (first.size in 1 until PAGE_SIZE) first.size else PAGE_SIZE
        val knownPages = if (totalCount > first.size) {
            (totalCount + effectivePage - 1) / effectivePage
        } else {
            0
        }

        if (knownPages <= 1 && first.size < PAGE_SIZE) {
            if (onUpdate != null) withContext(Dispatchers.Main) { onUpdate(first) }
            return@withContext first
        }

        if (knownPages <= 1) {
            // Количество треков в brief-info может отсутствовать или быть
            // неточным. Тогда идём по страницам, пока API не вернёт пустую.
            val all = ArrayList<Track>(first)
            val seen = HashSet<String>()
            all.forEach { seen.add(it.id) }
            if (onUpdate != null) withContext(Dispatchers.Main) { onUpdate(all.toList()) }
            var page = 1
            while (page < 200) {
                val tracks = fetchPage(artistId, page)
                if (tracks.isEmpty()) break
                var added = false
                tracks.forEach { track ->
                    if (seen.add(track.id)) {
                        all.add(track)
                        added = true
                    }
                }
                if (added && onUpdate != null) {
                    withContext(Dispatchers.Main) { onUpdate(all.toList()) }
                }
                if (tracks.size < PAGE_SIZE) break
                page++
            }
            return@withContext all
        }

        val pages = ConcurrentHashMap<Int, List<Track>>()
        pages[0] = first
        if (onUpdate != null) withContext(Dispatchers.Main) { onUpdate(first) }

        val semaphore = Semaphore(CONCURRENCY)
        coroutineScope {
            (1 until knownPages).map { page ->
                async {
                    semaphore.withPermit {
                        val tracks = fetchPage(artistId, page)
                        pages[page] = tracks
                        if (onUpdate != null && tracks.isNotEmpty()) {
                            val snapshot = mergePages(pages, knownPages)
                            withContext(Dispatchers.Main) { onUpdate(snapshot) }
                        }
                    }
                }
            }.awaitAll()
        }

        mergePages(pages, knownPages)
    }

    private suspend fun fetchPage(artistId: Long, page: Int): List<Track> {
        return try {
            parseTracks(ApiClient.api.getArtistTracks(artistId, page, PAGE_SIZE).result)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun mergePages(pages: Map<Int, List<Track>>, count: Int): List<Track> {
        val seen = HashSet<String>()
        val result = ArrayList<Track>()
        for (page in 0 until count) {
            pages[page].orEmpty().forEach { track ->
                if (seen.add(track.id)) result.add(track)
            }
        }
        return result
    }
}
