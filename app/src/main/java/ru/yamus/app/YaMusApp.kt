package ru.yamus.app

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import com.bumptech.glide.Glide
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.data.WaveHistoryStore
import ru.yamus.app.core.net.TlsSupport
import ru.yamus.app.core.player.MediaSessionController
import ru.yamus.app.core.player.PlaybackManager
import ru.yamus.app.core.player.TrackDownloader
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.CacheManager

class YaMusApp : Application() {

    private var startedActivities = 0
    private var cacheClearJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // Обязательно до первого HTTPS-запроса.
        TlsSupport.install()
        TokenStorage.init(this)
        WaveHistoryStore.init(this)
        ApiClient.token = (TokenStorage.token)
        PlaybackManager.init(this)
        MediaSessionController.init(this)
        registerActivityLifecycleCallbacks(activityCallbacks)
        AppScope.launch {
            // Glide инициализируем на главном потоке, как он того требует,
            // и только потом чистим его диск.
            runCatching { Glide.get(this@YaMusApp) }
            clearLeftoverCache()
            // Даём первому экрану спокойно отрисоваться.
            delay(3000)
            warmUp()
        }
    }

    /**
     * Страховка на случай, когда приложение закрыли принудительно: свайп из
     * недавних, «Остановить» в настройках системы или убийство процесса
     * системой не дают выполнить очистку при выходе. Поэтому каждый новый
     * запуск процесса начинается с пустого кэша — старые треки и обложки
     * не накапливаются между сеансами.
     */
    private suspend fun clearLeftoverCache() {
        try {
            CacheManager.clear(this)
        } catch (_: Exception) {
        }
        try {
            TrackDownloader.trimCache(this)
        } catch (_: Exception) {
        }
    }

    /**
     * Кэш треков и обложек очищается автоматически, когда пользователь
     * выходит из приложения. Не трогаем его во время воспроизведения:
     * иначе пропадёт предзагруженный трек и текущий поток.
     *
     * При смене темы или повороте экрана старая Activity тоже останавливается,
     * поэтому очистка откладывается на полсекунды — новая Activity успевает
     * стартовать и отменить её.
     */
    private val activityCallbacks = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

        override fun onActivityStarted(activity: Activity) {
            startedActivities++
            cacheClearJob?.cancel()
            cacheClearJob = null
        }

        override fun onActivityResumed(activity: Activity) {}

        override fun onActivityPaused(activity: Activity) {}

        override fun onActivityStopped(activity: Activity) {
            startedActivities = (startedActivities - 1).coerceAtLeast(0)
            if (startedActivities == 0) scheduleCacheClear()
        }

        override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

        override fun onActivityDestroyed(activity: Activity) {
            // Закрытие последнего экрана кнопкой «назад»: процесс может быть
            // убит сразу после onDestroy, поэтому не ждём отложенной очистки.
            if (activity.isFinishing && startedActivities == 0) clearCacheNow()
        }
    }

    private fun scheduleCacheClear() {
        cacheClearJob?.cancel()
        cacheClearJob = AppScope.launch {
            delay(500)
            if (startedActivities > 0) return@launch
            clearCacheNow()
        }
    }

    private fun clearCacheNow() {
        cacheClearJob?.cancel()
        cacheClearJob = null
        if (PlaybackManager.isPlaying || PlaybackManager.isPreparing) return
        AppScope.launch { CacheManager.clear(this@YaMusApp) }
    }

    /**
     * Когда приложение уходит в фон, освобождаем кэш картинок: на телефонах
     * с 512 МБ памяти каждый лишний мегабайт в heap заставляет систему
     * чаще запускать GC, из-за чего дёргается весь интерфейс.
     */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
            runCatching { Glide.get(this).clearMemory() }
        }
    }

    override fun onLowMemory() {
        super.onLowMemory()
        runCatching { Glide.get(this).clearMemory() }
    }

    /**
     * Заранее подгружает и проверяет классы, которые впервые используются
     * при прокрутке списков и запуске плеера.
     *
     * На Android 4.x Dalvik проверяет и оптимизирует классы при первом
     * обращении на том потоке, который к ним обратился. Если это происходит
     * в UI-потоке во время прокрутки, экран дёргается на десятки кадров.
     * Фоновая загрузка переносит эту работу на IO-поток.
     */
    private suspend fun warmUp() {
        val loader = classLoader
        for (name in WARMUP_CLASSES) {
            runCatching { Class.forName(name, true, loader) }
            // Между классами отдаём процессор интерфейсу, иначе проверка
            // классов идёт сплошным потоком и сама вызывает подвисание.
            delay(8)
        }
        // Полная инициализация движка Glide: пулы, дисковый кэш, потоки.
        runCatching { Glide.get(this) }
    }

    private companion object {
        val WARMUP_CLASSES = arrayOf(
            "com.bumptech.glide.Glide",
            "com.bumptech.glide.RequestManager",
            "com.bumptech.glide.request.RequestOptions",
            "com.bumptech.glide.request.BaseRequestOptions",
            "com.bumptech.glide.load.engine.DiskCacheStrategy",
            "com.bumptech.glide.load.resource.bitmap.RoundedCorners",
            "com.bumptech.glide.load.resource.bitmap.CircleCrop",
            "androidx.recyclerview.widget.RecyclerView",
            "androidx.recyclerview.widget.LinearLayoutManager",
            "androidx.recyclerview.widget.SimpleItemAnimator",
            "androidx.media.session.MediaSessionCompat",
            "androidx.media.session.MediaSessionCompat\$Callback",
            "androidx.media.session.MediaSessionCompat\$Token",
            "androidx.media.app.NotificationCompat\$MediaStyle",
            "android.support.v4.media.MediaMetadataCompat",
            "android.support.v4.media.MediaMetadataCompat\$Builder",
            "android.support.v4.media.session.PlaybackStateCompat",
            "android.support.v4.media.session.PlaybackStateCompat\$Builder",
            "androidx.core.app.NotificationCompat",
            "androidx.core.app.NotificationCompat\$Builder",
            "androidx.core.app.NotificationCompat\$Style",
            "androidx.core.app.NotificationCompat\$Action",
            "androidx.core.app.NotificationCompatBuilder",
            "ru.yamus.app.core.player.PlaybackService",
            "ru.yamus.app.core.player.StreamProxy"
        )
    }
}
