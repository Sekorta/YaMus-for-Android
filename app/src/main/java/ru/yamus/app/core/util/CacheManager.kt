package ru.yamus.app.core.util

import android.content.Context
import com.bumptech.glide.Glide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.yamus.app.core.player.TrackDownloader
import java.io.File

/** Общая очистка дискового кэша треков и обложек. */
object CacheManager {

    fun size(context: Context): Long {
        var total = TrackDownloader.cacheSize(context)
        total += dirSize(File(context.cacheDir, "glide-cache"))
        total += dirSize(File(context.cacheDir, "image_manager_disk_cache"))
        return total
    }

    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 МБ"
        val mb = bytes.toDouble() / (1024.0 * 1024.0)
        return if (mb < 1.0) {
            String.format("%.0f КБ", bytes.toDouble() / 1024.0)
        } else if (mb < 1024.0) {
            String.format("%.1f МБ", mb)
        } else {
            String.format("%.2f ГБ", mb / 1024.0)
        }
    }

    suspend fun clear(context: Context) {
        withContext(Dispatchers.IO) {
            TrackDownloader.clear(context)
            try {
                Glide.get(context).clearDiskCache()
            } catch (_: Exception) {
            }
        }
        try {
            Glide.get(context).clearMemory()
        } catch (_: Exception) {
        }
    }

    private fun dirSize(dir: File): Long = try {
        dir.listFiles()?.sumOf { if (it.isDirectory) dirSize(it) else it.length() } ?: 0L
    } catch (_: Exception) {
        0L
    }
}
