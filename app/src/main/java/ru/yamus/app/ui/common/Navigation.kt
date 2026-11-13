package ru.yamus.app.ui.common

import android.content.Context
import androidx.appcompat.app.AlertDialog
import ru.yamus.app.R
import ru.yamus.app.core.models.Track
import ru.yamus.app.ui.album.AlbumActivity
import ru.yamus.app.ui.artist.ArtistActivity

object Navigation {

    fun openAlbum(context: Context, albumId: Long, title: String? = null) {
        if (albumId <= 0) return
        try {
            context.startActivity(AlbumActivity.intent(context, albumId, title))
        } catch (_: Exception) {
        }
    }

    fun openArtist(context: Context, artistId: Long, name: String? = null) {
        if (artistId <= 0) return
        try {
            context.startActivity(ArtistActivity.intent(context, artistId, name))
        } catch (_: Exception) {
        }
    }

    /** У трека может быть несколько исполнителей — тогда показываем выбор. */
    fun openArtistChooser(context: Context, track: Track) {
        val artists = track.artists.orEmpty().filter { it.id > 0 }
        when {
            artists.isEmpty() -> {
            }
            artists.size == 1 -> openArtist(context, artists[0].id, artists[0].name)
            else -> {
                val names = artists.map { it.name ?: "Исполнитель" }.toTypedArray()
                val dialog = AlertDialog.Builder(context)
                    .setTitle(R.string.choose_artist)
                    .setItems(names) { _, which ->
                        val artist = artists.getOrNull(which) ?: return@setItems
                        openArtist(context, artist.id, artist.name)
                    }
                    .show()
                styleTerminalDialog(dialog)
            }
        }
    }
}
