package ru.yamus.app.core.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media.app.NotificationCompat.MediaStyle
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import ru.yamus.app.R
import ru.yamus.app.core.data.AccountStore
import ru.yamus.app.core.data.DislikesStore
import ru.yamus.app.core.data.LikesStore
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.CacheManager
import ru.yamus.app.core.util.artistText
import ru.yamus.app.core.util.coverUrl
import ru.yamus.app.core.util.displayTitle
import ru.yamus.app.ui.main.MainActivity
import java.util.concurrent.TimeUnit

/**
 * Foreground-сервис системного плеера.
 *
 * Показывает MediaStyle-уведомление и держит MediaSession. На Android 13+
 * системные элементы управления сами показывают время и полосу перемотки:
 * они берут позицию из [PlaybackStateCompat].
 */
class PlaybackService : Service() {

    companion object {
        const val CHANNEL_ID = "yamus_playback"
        private const val NOTIFICATION_ID = 1001

        private const val ACTION_UPDATE = "ru.yamus.app.action.PLAYER_UPDATE"
        private const val ACTION_PLAY_PAUSE = "ru.yamus.app.action.PLAYER_PLAY_PAUSE"
        private const val ACTION_NEXT = "ru.yamus.app.action.PLAYER_NEXT"
        private const val ACTION_PREV = "ru.yamus.app.action.PLAYER_PREV"
        private const val ACTION_STOP = "ru.yamus.app.action.PLAYER_STOP"
        private const val ACTION_LIKE = "ru.yamus.app.action.PLAYER_LIKE"
        private const val ACTION_DISLIKE = "ru.yamus.app.action.PLAYER_DISLIKE"
        private const val ACTION_SEEK = "ru.yamus.app.action.PLAYER_SEEK"
        private const val EXTRA_POSITION = "position"

        @Volatile
        private var running = false

        fun startOrUpdate(context: Context) {
            val intent = Intent(context, PlaybackService::class.java).setAction(ACTION_UPDATE)
            try {
                if (running) {
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            } catch (_: Exception) {
            }
        }

        fun stop(context: Context) {
            try {
                context.stopService(Intent(context, PlaybackService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    private var session: MediaSessionCompat? = null
    private var foregroundStarted = false
    private val handler = Handler(Looper.getMainLooper())

    private var largeIcon: Bitmap? = null
    private var largeIconTrackId: String? = null

    /** Что уже отправлено в MediaSession, чтобы не пересобирать метаданные ежесекундно. */
    private var metadataTrackId: String? = null
    private var metadataDuration: Long = -1L
    private var metadataIcon: Bitmap? = null

    // Держим иконки лайка/дизлайка в уведомлении в актуальном состоянии,
    // даже если трек лайкнули из самого приложения, а не из уведомления.
    private val likesListener: () -> Unit = {
        handler.post {
            if (running && PlaybackManager.currentTrack != null) publish()
        }
    }

    private val dislikesListener: () -> Unit = {
        handler.post {
            if (running && PlaybackManager.currentTrack != null) publish()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            if (PlaybackManager.currentTrack == null) return
            updateSessionState()
            if (PlaybackManager.isPlaying || PlaybackManager.isPreparing) {
                handler.postDelayed(this, tickerDelay)
            }
        }
    }

    /**
     * На Android 4.x системный плеер не показывает позицию, поэтому
     * ежесекундное обновление сессии не нужно — оно только мусорит в heap.
     */
    private val tickerDelay: Long =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) 1000L else 5000L

    private val sessionCallback = object : MediaSessionCompat.Callback() {
        override fun onPlay() = PlaybackManager.start()

        override fun onPause() = PlaybackManager.pause()

        override fun onStop() = PlaybackManager.stop()

        override fun onSkipToNext() = PlaybackManager.next()

        override fun onSkipToPrevious() = PlaybackManager.previous()

        override fun onSeekTo(pos: Long) = PlaybackManager.seekTo(pos.toInt())

        override fun onPlayFromMediaId(mediaId: String?, extras: android.os.Bundle?) {
            PlaybackManager.toggle()
        }

        // Android 13+ (и развёрнутые медиа-контролы на 11–12) вызывают
        // пользовательские действия через MediaSession, а не через
        // PendingIntent уведомления. Без этого лайк/дизлайк в системном
        // плеере нажимаются, но ничего не делают.
        override fun onCustomAction(action: String?, extras: android.os.Bundle?) {
            when (action) {
                ACTION_LIKE -> toggleLike()
                ACTION_DISLIKE -> toggleDislike()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        running = true
        createChannel()
        ensureSession()
        LikesStore.addListener(likesListener)
        DislikesStore.addListener(dislikesListener)
    }

    /**
     * Создаёт сессию один раз и не даёт ей «погаснуть».
     *
     * Android 14+ строит карточку плеера на экране блокировки по активной
     * MediaSession, привязанной к media-уведомлению. Если сессия к моменту
     * публикации уведомления неактивна (или её успели освободить),
     * SystemUI может показать уведомление без элементов управления.
     */
    @Suppress("DEPRECATION")
    private fun ensureSession() {
        val current = session
        if (current != null) {
            if (!current.isActive) current.isActive = true
            return
        }
        session = MediaSessionCompat(
            this,
            "YaMus",
            ComponentName(this, MediaButtonReceiver::class.java),
            null
        ).apply {
            setCallback(sessionCallback)
            // На Android 4.x эти флаги включают управление с экрана блокировки
            // через RemoteControlClient; на новых версиях они игнорируются.
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                    MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            // Без явного локального воспроизведения MediaController на
            // Android 14+ может считать сессию внешней (cast) — тогда
            // стандартные кнопки в системном плеере не строятся.
            setPlaybackToLocal(AudioManager.STREAM_MUSIC)
            // Тап по карточке на экране блокировки открывает приложение.
            setSessionActivity(contentIntent())
            isActive = true
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PLAY_PAUSE -> PlaybackManager.toggle()
            ACTION_NEXT -> PlaybackManager.next()
            ACTION_PREV -> PlaybackManager.previous()
            ACTION_LIKE -> toggleLike()
            ACTION_DISLIKE -> toggleDislike()
            ACTION_SEEK -> PlaybackManager.seekTo(
                intent.getIntExtra(EXTRA_POSITION, 0)
            )
            ACTION_STOP -> {
                PlaybackManager.stop()
                return START_NOT_STICKY
            }
        }

        if (PlaybackManager.currentTrack == null) {
            shutdown()
            return START_NOT_STICKY
        }
        publish()
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Задачу убрали из списка недавних. Процесс после этого может быть убит
     * в любой момент, поэтому если музыка не играет — очищаем кэш сразу,
     * не полагаясь на lifecycle экранов.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (PlaybackManager.isPlaying || PlaybackManager.isPreparing) return
        AppScope.launch {
            try {
                CacheManager.clear(this@PlaybackService)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        LikesStore.removeListener(likesListener)
        DislikesStore.removeListener(dislikesListener)
        running = false
        try {
            session?.isActive = false
            session?.release()
        } catch (_: Exception) {
        }
        session = null
        foregroundStarted = false
        super.onDestroy()
    }

    private fun toggleLike() {
        val track = PlaybackManager.currentTrack ?: return
        AppScope.launch {
            ensureLikesLoaded()
            val nowLiked = LikesStore.toggle(track)
            handler.post {
                if (PlaybackManager.currentTrack?.id == track.id) publish()
            }
            PlaybackManager.toast(
                if (nowLiked) "Добавлено в «Мне нравится»" else "Убрано из «Мне нравится»"
            )
        }
    }

    private fun toggleDislike() {
        val track = PlaybackManager.currentTrack ?: return
        AppScope.launch {
            ensureDislikesLoaded()
            val nowDisliked = DislikesStore.toggle(track)
            handler.post {
                if (PlaybackManager.currentTrack?.id == track.id) publish()
            }
            PlaybackManager.toast(
                if (nowDisliked) "Дизлайк поставлен" else "Дизлайк убран"
            )
        }
    }

    private suspend fun ensureLikesLoaded() {
        if (LikesStore.loaded) return
        val uid = AccountStore.uid
        if (uid > 0) LikesStore.load(uid)
    }

    private suspend fun ensureDislikesLoaded() {
        if (DislikesStore.loaded) return
        val uid = AccountStore.uid
        if (uid > 0) DislikesStore.load(uid)
    }

    /**
     * Обложка для уведомления грузится один раз на трек и переиспользуется
     * при каждом обновлении. NotificationManager не умеет тянуть картинки
     * сам, поэтому обложку кладём в notification.largeIcon.
     */
    private fun updateLargeIcon(track: Track) {
        if (track.id == largeIconTrackId) return
        largeIconTrackId = track.id
        largeIcon = null

        val url = track.coverUrl(300) ?: return
        AppScope.launch(Dispatchers.IO) {
            val bitmap = try {
                Glide.with(applicationContext)
                    .asBitmap()
                    .load(url)
                    .submit(200, 200)
                    .get(10, TimeUnit.SECONDS)
            } catch (_: Exception) {
                null
            }
            if (bitmap == null) return@launch
            handler.post {
                if (largeIconTrackId == track.id) {
                    largeIcon = bitmap
                    if (PlaybackManager.currentTrack?.id == track.id) publish()
                }
            }
        }
    }

    private fun publish() {
        val track = PlaybackManager.currentTrack ?: run {
            shutdown()
            return
        }

        ensureSession()
        updateLargeIcon(track)
        updateSessionState()
        val notification = buildNotification(track)
        if (!foregroundStarted) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                foregroundStarted = true
            } catch (_: Exception) {
                // Даже если система не разрешила foreground-сервис, плеер
                // продолжает работать. Пробуем показать обычное уведомление:
                // на Android 13+ карточка на экране блокировки строится по нему.
                try {
                    val manager =
                        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                } catch (_: Exception) {
                }
            }
        } else {
            try {
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, notification)
            } catch (_: Exception) {
            }
        }

        handler.removeCallbacks(ticker)
        if (PlaybackManager.isPlaying || PlaybackManager.isPreparing) {
            handler.postDelayed(ticker, tickerDelay)
        }
    }

    private fun updateSessionState() {
        val session = session ?: return
        val track = PlaybackManager.currentTrack ?: return

        // Длительность из MediaPlayer точнее, чем из ответа API. Если плеер
        // ещё готовит трек — берём метаданные трека.
        val playerDuration = PlaybackManager.duration().toLong()
        val duration = if (playerDuration > 0) playerDuration else track.durationMs

        val icon = largeIcon
        // Метаданные (вместе с обложкой) отправляем только при смене трека,
        // длительности или обложки. Ежесекундная пересборка бандла с
        // картинкой гоняла через Binder сотни килобайт и провоцировала GC.
        if (metadataTrackId != track.id || metadataDuration != duration || metadataIcon !== icon) {
            metadataTrackId = track.id
            metadataDuration = duration
            metadataIcon = icon

            val metadataBuilder = MediaMetadataCompat.Builder()
                .putString(MediaMetadataCompat.METADATA_KEY_TITLE, track.displayTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, track.artistText())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_TITLE, track.displayTitle())
                .putString(MediaMetadataCompat.METADATA_KEY_DISPLAY_SUBTITLE, track.artistText())
            track.albums?.firstOrNull()?.title?.takeIf { it.isNotBlank() }?.let {
                metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, it)
            }
            if (duration > 0) {
                metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            }
            // Android 13+ берёт обложку в системном медиаплеере не из
            // notification.largeIcon, а из метаданных MediaSession.
            icon?.let { art ->
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, art)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ART, art)
                metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_DISPLAY_ICON, art)
            }
            session.setMetadata(metadataBuilder.build())
        }

        val state = when {
            PlaybackManager.isPreparing -> PlaybackStateCompat.STATE_BUFFERING
            PlaybackManager.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val actions = PlaybackStateCompat.ACTION_PLAY or
            PlaybackStateCompat.ACTION_PAUSE or
            PlaybackStateCompat.ACTION_PLAY_PAUSE or
            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
            PlaybackStateCompat.ACTION_SEEK_TO or
            PlaybackStateCompat.ACTION_STOP

        val builder = PlaybackStateCompat.Builder()
            .setActions(actions)
            .setState(
                state,
                PlaybackManager.position().toLong(),
                if (PlaybackManager.isPlaying) 1f else 0f
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_LIKE,
                    getString(if (LikesStore.isLiked(track.id)) R.string.track_unlike else R.string.track_like),
                    if (LikesStore.isLiked(track.id)) R.drawable.ic_notif_like_on else R.drawable.ic_notif_like
                ).build()
            )
            .addCustomAction(
                PlaybackStateCompat.CustomAction.Builder(
                    ACTION_DISLIKE,
                    getString(
                        if (DislikesStore.isDisliked(track.id)) R.string.track_undislike
                        else R.string.track_dislike
                    ),
                    if (DislikesStore.isDisliked(track.id)) R.drawable.ic_notif_dislike_on
                    else R.drawable.ic_notif_dislike
                ).build()
            )
        if (duration > 0) builder.setBufferedPosition(duration)
        session.setPlaybackState(builder.build())
    }

    private fun buildNotification(track: Track): Notification {
        val liked = LikesStore.isLiked(track.id)
        val disliked = DislikesStore.isDisliked(track.id)
        val playing = PlaybackManager.isPlaying
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setLargeIcon(largeIcon)
            .setContentTitle(track.displayTitle())
            .setContentText(track.artistText())
            .setContentIntent(contentIntent())
            .setDeleteIntent(serviceIntent(ACTION_STOP, 90))
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setOngoing(true)
            .setSilent(true)
            .setStyle(
                MediaStyle()
                    .setMediaSession(session?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        // Порядок важен для Android 4.x: там помещаются только первые три
        // действия, поэтому вперёд идут перемотка и play/pause.
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.ic_notif_prev,
                getString(R.string.previous),
                serviceIntent(ACTION_PREV, 11)
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                if (playing) R.drawable.ic_notif_pause else R.drawable.ic_notif_play,
                getString(if (playing) R.string.pause else R.string.play),
                serviceIntent(ACTION_PLAY_PAUSE, 12)
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                R.drawable.ic_notif_next,
                getString(R.string.next),
                serviceIntent(ACTION_NEXT, 13)
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                if (liked) R.drawable.ic_notif_like_on else R.drawable.ic_notif_like,
                getString(if (liked) R.string.track_unlike else R.string.track_like),
                serviceIntent(ACTION_LIKE, 10)
            )
        )
        builder.addAction(
            NotificationCompat.Action(
                if (disliked) R.drawable.ic_notif_dislike_on else R.drawable.ic_notif_dislike,
                getString(
                    if (disliked) R.string.track_undislike
                    else R.string.track_dislike
                ),
                serviceIntent(ACTION_DISLIKE, 14)
            )
        )
        return builder.build()
    }

    private fun contentIntent(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(this, 100, intent, pendingFlags())
    }

    private fun serviceIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, PlaybackService::class.java).setAction(action),
            pendingFlags()
        )
    }

    private fun pendingFlags(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        manager.createNotificationChannel(channel)
    }

    private fun shutdown() {
        handler.removeCallbacks(ticker)
        try {
            if (foregroundStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else if (foregroundStarted) {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        foregroundStarted = false
        stopSelf()
    }
}
