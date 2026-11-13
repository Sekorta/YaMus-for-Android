package ru.yamus.app.ui.common

import android.content.Context
import androidx.appcompat.app.AlertDialog
import kotlinx.coroutines.launch
import ru.yamus.app.R
import ru.yamus.app.core.data.AccountStore
import ru.yamus.app.core.data.PlaylistRepository
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.util.AppScope

object AddToPlaylistDialog {

    fun show(context: Context, track: Track) {
        val uid = AccountStore.uid
        if (uid <= 0) {
            PlaybackManager.toast("Аккаунт ещё не загружен")
            return
        }

        AppScope.launch {
            val playlists = PlaylistRepository.loadPlaylists(uid)
            if (playlists.isEmpty()) {
                PlaybackManager.toast(context.getString(R.string.no_playlists))
                return@launch
            }
            val titles = playlists.map { it.title?.takeIf { t -> t.isNotBlank() } ?: "Плейлист" }
                .toTypedArray()

            val dialog = AlertDialog.Builder(context)
                .setTitle(R.string.add_to_playlist_title)
                .setItems(titles) { _, which ->
                    val playlist = playlists.getOrNull(which) ?: return@setItems
                    AppScope.launch {
                        val ok = PlaylistRepository.addTrack(uid, playlist, track)
                        PlaybackManager.toast(
                            if (ok) "Добавлено в «${playlist.title ?: "плейлист"}»"
                            else "Не удалось добавить трек"
                        )
                    }
                }
                .setNegativeButton(R.string.cancel, null)
                .show()
            styleTerminalDialog(dialog)
        }
    }
}
