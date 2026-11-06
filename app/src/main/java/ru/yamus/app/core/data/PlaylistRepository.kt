package ru.yamus.app.core.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.models.PlaylistSummary
import ru.yamus.app.core.models.Track

object PlaylistRepository {

    suspend fun loadPlaylists(uid: Long): List<PlaylistSummary> = withContext(Dispatchers.IO) {
        try {
            ApiClient.api.getPlaylists(uid).result.orEmpty()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun addTrack(uid: Long, playlist: PlaylistSummary, track: Track): Boolean =
        withContext(Dispatchers.IO) {
            if (uid <= 0 || playlist.kind <= 0) return@withContext false
            val albumId = track.mainAlbumIdSafe()
            val diff = "{\"diff\":{\"op\":\"insert\",\"at\":0,\"tracks\":[{\"id\":\"${track.id}\",\"albumId\":\"$albumId\"}]}}"
            var revision = playlist.revision ?: 0
            var attempts = 0
            while (attempts < 2) {
                try {
                    ApiClient.api.changePlaylistRelative(uid, playlist.kind, diff, revision.toString())
                    return@withContext true
                } catch (e: Exception) {
                    attempts++
                    if (attempts >= 2) return@withContext false
                    try {
                        val detail = ApiClient.api.getPlaylist(uid, playlist.kind).result
                        revision = detail?.revision ?: revision
                    } catch (_: Exception) {
                        return@withContext false
                    }
                }
            }
            false
        }

    private fun Track.mainAlbumIdSafe(): Long =
        albums?.firstOrNull()?.id ?: 0L
}
