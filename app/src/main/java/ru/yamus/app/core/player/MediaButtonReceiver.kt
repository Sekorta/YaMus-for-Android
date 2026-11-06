package ru.yamus.app.core.player

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent

/**
 * Приёмник кнопок гарнитуры и Bluetooth для Android 4.x.
 *
 * На API < 21 [android.support.v4.media.session.MediaSessionCompat] требует
 * явный компонент медиа-приёмника: без него конструктор падает с
 * `IllegalArgumentException: MediaButtonReceiver component may not be null`.
 * Этот приёмник объявлен в манифесте и передаётся сессии при создании.
 *
 * События приходят, пока наш RemoteControlClient активен. Обрабатываем их
 * напрямую через [PlaybackManager] — сервис уже работает, пока играет трек.
 */
class MediaButtonReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")
    override fun onReceive(context: Context?, intent: Intent?) {
        if (intent?.action != Intent.ACTION_MEDIA_BUTTON) return
        val event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT) as? KeyEvent ?: return
        // Обрабатываем только нажатие: KEY_UP пришёл бы вторым и переключил
        // play/pause обратно.
        if (event.action != KeyEvent.ACTION_DOWN) return

        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY -> PlaybackManager.start()
            KeyEvent.KEYCODE_MEDIA_PAUSE -> PlaybackManager.pause()
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_HEADSETHOOK -> PlaybackManager.toggle()
            KeyEvent.KEYCODE_MEDIA_NEXT -> PlaybackManager.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> PlaybackManager.previous()
            KeyEvent.KEYCODE_MEDIA_STOP -> PlaybackManager.stop()
        }
    }
}
