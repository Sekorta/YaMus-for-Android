package ru.yamus.app.ui.common

import android.content.Context
import ru.yamus.app.core.links.MusicDestination
import ru.yamus.app.ui.album.AlbumActivity
import ru.yamus.app.ui.playlist.PlaylistActivity

/**
 * Открытие экранов по разобранной ссылке и запоминание ссылки, если
 * пользователь ещё не вошёл в аккаунт.
 */
object DeepLinks {

    private const val PREFS = "yamus_deep_links"
    private const val KEY_PENDING = "pending_url"
    private const val KEY_PENDING_TIME = "pending_time"

    /** Незавершённый вход не должен открывать случайную старую ссылку. */
    private const val PENDING_TTL_MS = 60L * 60L * 1000L

    fun open(context: Context, destination: MusicDestination) {
        when (destination) {
            is MusicDestination.Album -> {
                Navigation.openAlbum(context, destination.albumId)
            }

            is MusicDestination.Track -> {
                try {
                    context.startActivity(
                        AlbumActivity.trackIntent(context, destination.trackId)
                    )
                } catch (_: Exception) {
                }
            }

            is MusicDestination.Artist -> {
                Navigation.openArtist(context, destination.artistId)
            }

            is MusicDestination.Playlist -> {
                try {
                    context.startActivity(
                        PlaylistActivity.userIntent(
                            context,
                            destination.user,
                            destination.kind
                        )
                    )
                } catch (_: Exception) {
                }
            }
        }
    }

    fun savePending(context: Context, url: String) {
        if (url.isBlank()) return
        prefs(context).edit()
            .putString(KEY_PENDING, url)
            .putLong(KEY_PENDING_TIME, System.currentTimeMillis())
            .apply()
    }

    fun takePending(context: Context): String? {
        val prefs = prefs(context)
        val url = prefs.getString(KEY_PENDING, null)
        if (url.isNullOrBlank()) return null
        val savedAt = prefs.getLong(KEY_PENDING_TIME, 0L)
        prefs.edit()
            .remove(KEY_PENDING)
            .remove(KEY_PENDING_TIME)
            .apply()
        if (savedAt <= 0L || System.currentTimeMillis() - savedAt > PENDING_TTL_MS) {
            return null
        }
        return url
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
