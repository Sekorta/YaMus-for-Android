package ru.yamus.app

import android.app.ActivityManager
import android.content.Context
import com.bumptech.glide.GlideBuilder
import com.bumptech.glide.annotation.GlideModule
import com.bumptech.glide.load.DecodeFormat
import com.bumptech.glide.load.engine.bitmap_recycle.LruBitmapPool
import com.bumptech.glide.load.engine.cache.InternalCacheDiskCacheFactory
import com.bumptech.glide.load.engine.cache.LruResourceCache
import com.bumptech.glide.module.AppGlideModule
import com.bumptech.glide.request.RequestOptions

/**
 * Ограничивает кэш картинок. По умолчанию Glide может занять 250 МБ диска
 * и заметную долю heap — на старых устройствах это лишняя нагрузка.
 *
 * Пул битмапов нужен ради скругления обложек: каждый RoundedCorners создаёт
 * новую картинку, и без пула такие картинки тут же становятся мусором,
 * провоцируя частые паузы GC во время прокрутки списков.
 */
@GlideModule
class YaMusGlideModule : AppGlideModule() {

    override fun applyOptions(context: Context, builder: GlideBuilder) {
        builder.setDiskCache(
            InternalCacheDiskCacheFactory(context, "glide-cache", 64L * 1024L * 1024L)
        )
        try {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val cacheBytes = (manager.memoryClass.toLong() * 1024L * 1024L / 8L)
                .coerceIn(1536L * 1024L, 2L * 1024L * 1024L)
            builder.setMemoryCache(LruResourceCache(cacheBytes))
            // Пул битмапов реально переиспользует память только с Android 4.4
            // (inBitmap); на 4.x он лишь держит лишние мегабайты в heap.
            if (android.os.Build.VERSION.SDK_INT >= 19) {
                builder.setBitmapPool(LruBitmapPool(cacheBytes / 2L))
            }
            // RGB_565 вдвое уменьшает память под обложки: на старом телефоне
            // каждый лишний мегабайт приближает паузы GC при прокрутке.
            builder.setDefaultRequestOptions(
                RequestOptions().format(DecodeFormat.PREFER_RGB_565)
            )
        } catch (_: Exception) {
        }
    }

    override fun isManifestParsingEnabled(): Boolean = false
}
