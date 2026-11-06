package ru.yamus.app.core.player

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.yamus.app.core.auth.TokenStorage
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Скачивает трек в кэш и расшифровывает его.
 *
 * Резервный путь для устройств, где MediaPlayer не умеет играть HTTP-потоки.
 * Например, системный образ Android 4.0.3 собран без Chromium HTTP source:
 * `HTTPBase::Create` вызывает `TRESPASS`, media server падает и приложение
 * получает `error (100, 0)`. Локальный файл в таком случае играется нормально.
 *
 * Кэш ограничен не только числом файлов, но и размером: на старых телефонах
 * пара десятков FLAC-файлов легко занимает сотни мегабайт. Старые и давно
 * не использованные файлы удаляются автоматически.
 */
object TrackDownloader {

    private const val DIR = "track-cache"
    private const val MAX_FILES = 60
    private const val MAX_AGE_MS = 14L * 24L * 60L * 60L * 1000L
    private const val MAX_BYTES = 128L * 1024L * 1024L
    private const val MAX_BYTES_LOW = 48L * 1024L * 1024L
    private const val MAX_BYTES_MEDIUM = 80L * 1024L * 1024L

    /**
     * Блокировки по имени файла: один и тот же трек может одновременно
     * догружаться предзагрузкой и запрашиваться плеером. Без блокировки два
     * потока писали бы один `.part` и портили кэш.
     */
    private val fileLocks = java.util.concurrent.ConcurrentHashMap<String, Any>()

    private fun lockFor(name: String): Any {
        val fresh = Any()
        return fileLocks.putIfAbsent(name, fresh) ?: fresh
    }

    suspend fun download(
        context: Context,
        trackId: String,
        source: StreamProxy.Source
    ): File? = withContext(Dispatchers.IO) {
        if (trackId.isBlank()) return@withContext null

        val dir = cacheDir(context)
        if (!dir.exists() && !dir.mkdirs()) return@withContext null

        val target = File(dir, fileName(trackId, source))
        if (target.length() > 0) return@withContext target

        synchronized(lockFor(target.name)) {
            if (target.length() > 0) return@withContext target
            downloadTo(dir, target, source)
        }
    }

    private fun downloadTo(
        dir: File,
        target: File,
        source: StreamProxy.Source
    ): File? {
        val temp = File(dir, target.name + ".part")
        // Предзагрузка следующего трека идёт на полной скорости сети и
        // расшифровывается программным AES. На старых телефонах это
        // отбирает процессор у интерфейса, поэтому уводим поток в фон.
        try {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND
            )
        } catch (_: Exception) {
        }
        try {
            val connection = (URL(source.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "*/*")
            }
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return null
            }

            val cipher = source.keyHex
                ?.takeIf { it.isNotBlank() }
                ?.let { StreamProxy.createCipher(it, 0L) }

            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    // Расшифровка в переиспользуемый буфер: на Android 4.x
                    // каждый cipher.update() с массивом создаёт мусор, из-за
                    // которого GC подтормаживает интерфейс во время загрузки.
                    val plain = if (cipher != null) ByteArray(64 * 1024 + 32) else null
                    var useOffsetUpdate = cipher != null
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (cipher == null || plain == null) {
                            output.write(buffer, 0, read)
                            continue
                        }
                        if (useOffsetUpdate) {
                            val count = try {
                                cipher.update(buffer, 0, read, plain, 0)
                            } catch (_: Exception) {
                                useOffsetUpdate = false
                                -1
                            }
                            if (useOffsetUpdate) {
                                if (count > 0) output.write(plain, 0, count)
                                continue
                            }
                        }
                        val data = cipher.update(buffer, 0, read) ?: continue
                        if (data.isNotEmpty()) output.write(data)
                    }
                }
            }
            connection.disconnect()

            if (temp.length() <= 0) {
                temp.delete()
                return null
            }
            if (!temp.renameTo(target)) {
                temp.delete()
                return null
            }
            target.setLastModified(System.currentTimeMillis())
            trim(dir, maxBytesFor(dir))
            return target
        } catch (_: Exception) {
            temp.delete()
            return null
        }
    }

    fun clear(context: Context) {
        try {
            cacheDir(context).deleteRecursively()
        } catch (_: Exception) {
        }
    }

    /** Уже скачанный трек нужного качества, если он есть в кэше. */
    suspend fun cachedFile(context: Context, trackId: String): File? =
        withContext(Dispatchers.IO) {
            if (trackId.isBlank()) return@withContext null
            val dir = cacheDir(context)
            if (!dir.exists()) return@withContext null
            val prefix = "$trackId-${TokenStorage.quality}."
            dir.listFiles()?.firstOrNull {
                it.isFile &&
                    !it.name.endsWith(".part") &&
                    it.name.startsWith(prefix) &&
                    it.length() > 0
            }?.also {
                // Отмечаем файл как недавно использованный: кэш работает по LRU.
                it.setLastModified(System.currentTimeMillis())
            }
        }

    /** Удаляет битый файл трека, чтобы следующая попытка скачала его заново. */
    fun invalidate(context: Context, trackId: String) {
        if (trackId.isBlank()) return
        try {
            cacheDir(context).listFiles()
                ?.filter { it.name.startsWith("$trackId-") }
                ?.forEach { it.delete() }
        } catch (_: Exception) {
        }
    }

    fun cacheSize(context: Context): Long {
        return try {
            cacheDir(context).listFiles()?.sumOf { it.length() } ?: 0L
        } catch (_: Exception) {
            0L
        }
    }

    /** Периодическая уборка: вызывается при старте приложения. */
    suspend fun trimCache(context: Context) = withContext(Dispatchers.IO) {
        trim(cacheDir(context), maxBytes(context))
    }

    private fun cacheDir(context: Context): File = File(context.cacheDir, DIR)

    private fun maxBytes(context: Context): Long = maxBytesFor(cacheDir(context))

    private fun maxBytesFor(dir: File): Long {
        return try {
            val free = dir.usableSpace
            when {
                free < 250L * 1024L * 1024L -> MAX_BYTES_LOW
                free < 1024L * 1024L * 1024L -> MAX_BYTES_MEDIUM
                else -> MAX_BYTES
            }
        } catch (_: Exception) {
            MAX_BYTES_MEDIUM
        }
    }

    /** LRU-очистка треков и недокачанных .part-файлов. */
    private fun trim(dir: File, maxBytes: Long) {
        try {
            val now = System.currentTimeMillis()
            val files = dir.listFiles()?.filter { it.isFile } ?: return

            files.filter {
                it.name.endsWith(".part") && now - it.lastModified() > 60L * 60L * 1000L
            }.forEach { it.delete() }

            val completed = files
                .filter { !it.name.endsWith(".part") }
                .sortedBy { it.lastModified() }

            completed.filter { now - it.lastModified() > MAX_AGE_MS }
                .forEach { it.delete() }

            var total = completed.filter { it.exists() }.sumOf { it.length() }
            var count = completed.count { it.exists() }
            for (file in completed) {
                if (total <= maxBytes && count <= MAX_FILES) break
                if (!file.exists()) continue
                total -= file.length()
                count--
                file.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun fileName(trackId: String, source: StreamProxy.Source): String {
        val ext = when {
            source.contentType.contains("flac") -> "flac"
            source.contentType.contains("mp4") -> "m4a"
            source.contentType.contains("aac") -> "aac"
            else -> "mp3"
        }
        return "$trackId-${TokenStorage.quality}.$ext"
    }
}
