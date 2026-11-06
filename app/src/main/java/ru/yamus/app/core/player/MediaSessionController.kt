package ru.yamus.app.core.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import ru.yamus.app.core.data.DislikesStore
import ru.yamus.app.core.data.LikesStore

/**
 * Связывает [PlaybackManager] с системным плеером в шторке уведомлений.
 *
 * Вся тяжёлая работа (MediaSession, уведомление, foreground service) живёт
 * в [PlaybackService]. Контроллер только сообщает сервису, что состояние
 * изменилось, и не даёт ему запускаться, когда ничего не играет.
 */
object MediaSessionController {

    private var appContext: Context? = null
    private val handler = Handler(Looper.getMainLooper())

    private val playbackListener = object : PlaybackManager.Listener {
        override fun onPlaybackChanged() {
            handler.post { sync() }
        }
    }

    private val likesListener: () -> Unit = { handler.post { refresh() } }
    private val dislikesListener: () -> Unit = { handler.post { refresh() } }

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        PlaybackManager.addListener(playbackListener)
        LikesStore.addListener(likesListener)
        DislikesStore.addListener(dislikesListener)
    }

    /** Обновляет уведомление, даже если изменился только лайк/дизлайк. */
    fun refresh() {
        val context = appContext ?: return
        if (PlaybackManager.currentTrack == null) return
        PlaybackService.startOrUpdate(context)
    }

    private fun sync() {
        val context = appContext ?: return
        if (PlaybackManager.currentTrack == null) {
            PlaybackService.stop(context)
        } else {
            PlaybackService.startOrUpdate(context)
        }
    }
}
