package ru.yamus.app.ui.common

import android.app.Activity
import android.content.Context
import kotlinx.coroutines.launch
import ru.yamus.app.R
import ru.yamus.app.core.data.DislikesStore
import ru.yamus.app.core.data.LikesStore
import ru.yamus.app.core.download.OfflineLibrary
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.player.WaveController
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.displayTitle
import ru.yamus.app.core.util.mainAlbumId

/** Меню «три точки» для трека — одинаковое во всех списках и в плеере. */
object TrackOptionsDialog {

    fun show(context: Context, track: Track) {
        val groups = ArrayList<List<MenuDialog.Item>>()

        // 1. Библиотека: то, что чаще всего нужно сделать с треком.
        groups.add(
            listOf(
                MenuDialog.Item(
                    context.getString(
                        if (LikesStore.isLiked(track.id)) R.string.track_unlike
                        else R.string.track_like
                    )
                ) {
                    AppScope.launch {
                        val nowLiked = LikesStore.toggle(track)
                        PlaybackManager.toast(
                            if (nowLiked) "Добавлено в «Мне нравится»"
                            else "Убрано из «Мне нравится»"
                        )
                    }
                },
                MenuDialog.Item(
                    context.getString(
                        if (DislikesStore.isDisliked(track.id)) R.string.track_undislike
                        else R.string.track_dislike
                    )
                ) {
                    AppScope.launch {
                        val nowDisliked = DislikesStore.toggle(track)
                        PlaybackManager.toast(
                            if (nowDisliked) "Дизлайк поставлен"
                            else "Дизлайк убран"
                        )
                    }
                }
            )
        )

        // 2. Офлайн-библиотека: скачать или удалить файл с телефона.
        groups.add(offlineItems(context, track))

        // 3. Действия с очередью воспроизведения.
        groups.add(
            listOf(
                MenuDialog.Item(context.getString(R.string.track_play_next)) {
                    PlaybackManager.playNext(track)
                    PlaybackManager.toast("Играет следующим")
                },
                MenuDialog.Item(context.getString(R.string.track_add_playlist)) {
                    AddToPlaylistDialog.show(context, track)
                },
                MenuDialog.Item(context.getString(R.string.track_wave)) {
                    WaveController.startTrackRadio(track)
                    PlaybackManager.toast("Включаю волну по треку")
                }
            )
        )

        // 3. Ссылка на трек в буфер обмена.
        groups.add(
            listOf(
                MenuDialog.Item(context.getString(R.string.track_share)) {
                    ShareLink.copy(context, ShareLink.track(track))
                }
            )
        )

        // 4. Переходы на связанные страницы.
        val navigation = ArrayList<MenuDialog.Item>()
        val albumId = track.mainAlbumId()
        if (albumId > 0) {
            navigation.add(
                MenuDialog.Item(context.getString(R.string.track_album)) {
                    Navigation.openAlbum(context, albumId, track.albums?.firstOrNull()?.title)
                }
            )
        }
        if (track.artists.orEmpty().any { it.id > 0 }) {
            navigation.add(
                MenuDialog.Item(context.getString(R.string.track_artist)) {
                    Navigation.openArtistChooser(context, track)
                }
            )
        }
        groups.add(navigation)

        MenuDialog.show(context, track.displayTitle(), groups)
    }

    private fun offlineItems(context: Context, track: Track): List<MenuDialog.Item> {
        val entry = OfflineLibrary.find(track)
        if (entry != null) {
            return listOf(
                MenuDialog.Item(context.getString(R.string.delete_download)) {
                    OfflineLibrary.removeEntry(context, entry)
                    PlaybackManager.toast(context.getString(R.string.download_deleted))
                }
            )
        }
        return when (OfflineLibrary.stateOf(track)) {
            OfflineLibrary.State.DOWNLOADING,
            OfflineLibrary.State.QUEUED -> listOf(
                MenuDialog.Item(context.getString(R.string.downloading)) {
                    PlaybackManager.toast(context.getString(R.string.downloading))
                }
            )
            OfflineLibrary.State.FAILED -> listOf(
                MenuDialog.Item(
                    context.getString(R.string.download_failed) + " · " +
                        context.getString(R.string.track_retry_download)
                ) {
                    startDownload(context, track)
                }
            )
            else -> listOf(
                MenuDialog.Item(context.getString(R.string.download_track)) {
                    startDownload(context, track)
                }
            )
        }
    }

    private fun startDownload(context: Context, track: Track) {
        val activity = context as? Activity
        if (activity != null && OfflineLibrary.needsWritePermission(activity)) {
            StoragePermission.ensureWrite(activity) {
                if (OfflineLibrary.downloadNow(activity, track)) {
                    PlaybackManager.toast(activity.getString(R.string.download_started))
                }
            }
            return
        }
        if (OfflineLibrary.downloadNow(context, track)) {
            PlaybackManager.toast(context.getString(R.string.download_started))
        }
    }
}
