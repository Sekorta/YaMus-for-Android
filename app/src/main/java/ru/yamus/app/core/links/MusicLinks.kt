package ru.yamus.app.core.links

import android.net.Uri

/**
 * Куда ведёт ссылка на Яндекс Музыку.
 *
 * Трек сам по себе в приложении отдельного экрана не имеет, поэтому ссылка
 * на трек превращается в альбом: либо альбом уже указан в ссылке, либо его
 * нужно найти по идентификатору трека.
 */
sealed class MusicDestination {

    data class Album(
        val albumId: Long,
        val trackId: String? = null
    ) : MusicDestination()

    data class Track(val trackId: String) : MusicDestination()

    data class Artist(val artistId: Long) : MusicDestination()

    /** [user] — логин или числовой uid; null означает текущего пользователя. */
    data class Playlist(val user: String?, val kind: Long) : MusicDestination()
}

/**
 * Разбор ссылок вида:
 *  - https://music.yandex.ru/track/12345
 *  - https://music.yandex.ru/album/12345
 *  - https://music.yandex.ru/album/12345/track/67890
 *  - https://music.yandex.ru/artist/12345
 *  - https://music.yandex.ru/users/login/playlists/3
 *  - https://music.yandex.ru/playlists/3
 *
 * Ссылки с других доменов и неизвестные разделы считаются неподдерживаемыми.
 */
object MusicLinks {

    private val HOSTS = setOf(
        "music.yandex.ru",
        "music.yandex.com",
        "music.yandex.by",
        "music.yandex.kz",
        "music.yandex.uz",
        "music.yandex.com.tr"
    )

    fun parse(uri: Uri?): MusicDestination? {
        if (uri == null) return null
        val scheme = uri.scheme?.lowercase() ?: return null
        if (scheme != "http" && scheme != "https") return null
        val host = uri.host?.lowercase() ?: return null
        if (host !in HOSTS) return null
        return parseSegments(uri.pathSegments.orEmpty())
    }

    fun parse(url: String?): MusicDestination? {
        if (url.isNullOrBlank()) return null
        return try {
            parse(Uri.parse(url))
        } catch (_: Exception) {
            null
        }
    }

    private fun parseSegments(segments: List<String>): MusicDestination? {
        if (segments.isEmpty()) return null

        when (segments[0]) {
            "track" -> {
                val id = segments.getOrNull(1)?.trim()
                if (!id.isNullOrEmpty()) return MusicDestination.Track(id)
            }

            "album" -> {
                val albumId = segments.getOrNull(1)?.toLongOrNull() ?: return null
                if (albumId <= 0) return null
                val trackId = if (segments.getOrNull(2) == "track") {
                    segments.getOrNull(3)?.trim()?.takeIf { it.isNotEmpty() }
                } else {
                    null
                }
                return MusicDestination.Album(albumId, trackId)
            }

            "artist" -> {
                val artistId = segments.getOrNull(1)?.toLongOrNull() ?: return null
                if (artistId > 0) return MusicDestination.Artist(artistId)
            }

            "users" -> {
                val user = segments.getOrNull(1)?.trim()
                if (user.isNullOrEmpty()) return null
                if (segments.getOrNull(2) == "playlists") {
                    val kind = segments.getOrNull(3)?.toLongOrNull() ?: return null
                    if (kind > 0) return MusicDestination.Playlist(user, kind)
                }
            }

            "playlists" -> {
                val kind = segments.getOrNull(1)?.toLongOrNull() ?: return null
                if (kind > 0) return MusicDestination.Playlist(null, kind)
            }
        }

        return null
    }
}
