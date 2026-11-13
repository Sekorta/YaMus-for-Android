package ru.yamus.app.core.download

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/** Файл, уже опубликованный в пользовательской библиотеке. */
data class StoredFile(
    val uri: String?,
    val path: String?,
    val displayName: String,
    val size: Long
)

/**
 * Публичная папка «YaMus Library».
 *
 * Android 10+ не разрешает приложению создавать произвольные папки в корне
 * общей памяти, поэтому там библиотека живёт в стандартном каталоге музыки:
 * `Music/YaMus Library/<артист>/<альбом>/<артист> - <название>.<ext>`.
 * На Android 4–9 используется корень `/YaMus Library` — ровно так, как
 * ожидает пользователь.
 *
 * Файлы принадлежат пользователю: очистка кэша, выход из аккаунта и
 * переустановка приложения их не удаляют.
 */
object LibraryStorage {

    const val ROOT_NAME = "YaMus Library"
    private const val MUSIC_DIR = "Music"

    fun relativeRoot(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) "$MUSIC_DIR/$ROOT_NAME" else ROOT_NAME

    fun rootLabel(): String = relativeRoot()

    fun rootDir(): File = File(Environment.getExternalStorageDirectory(), ROOT_NAME)

    /** Фактический корень библиотеки для текущей версии Android. */
    fun publicRootDir(): File =
        File(Environment.getExternalStorageDirectory(), relativeRoot())

    fun publish(
        context: Context,
        source: File,
        artist: String,
        album: String,
        fileName: String,
        mime: String
    ): StoredFile? {
        if (!source.exists() || source.length() <= 0) return null
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            Api29.publish(context, source, artist, album, fileName, mime)
        } else {
            publishLegacy(context, source, artist, album, fileName)
        }
    }

    private fun publishLegacy(
        context: Context,
        source: File,
        artist: String,
        album: String,
        fileName: String
    ): StoredFile? {
        return try {
            val dir = File(File(rootDir(), artist), album)
            if (!dir.exists() && !dir.mkdirs()) return null
            val target = File(dir, fileName)
            if (!target.exists() || target.length() <= 0) {
                FileInputStream(source).use { input ->
                    FileOutputStream(target).use { output -> input.copyTo(output) }
                }
            }
            if (target.length() <= 0) {
                target.delete()
                return null
            }
            try {
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(target.absolutePath),
                    arrayOf(mimeTypeForName(fileName)),
                    null
                )
            } catch (_: Exception) {
            }
            StoredFile(null, target.absolutePath, fileName, target.length())
        } catch (_: Exception) {
            null
        }
    }

    fun delete(context: Context, entry: OfflineEntry): Boolean {
        // Папку вычисляем до удаления: после resolver.delete() content://
        // уже не отдаст RELATIVE_PATH.
        val directory = directoryOf(context, entry)
        val uri = entry.uri
        val deleted = if (!uri.isNullOrBlank()) {
            try {
                context.contentResolver.delete(Uri.parse(uri), null, null) > 0
            } catch (_: Exception) {
                false
            }
        } else {
            val path = entry.path ?: return false
            try {
                File(path).delete()
            } catch (_: Exception) {
                false
            }
        }
        // Убираем опустевшие папки альбома и артиста, чтобы в библиотеке не
        // оставались пустые каталоги. Если в папке ещё есть файлы или
        // система не разрешает удаление каталога, она просто останется.
        pruneEmptyDirs(directory)
        return deleted
    }

    private fun directoryOf(context: Context, entry: OfflineEntry): File? {
        val path = entry.path
        if (!path.isNullOrBlank()) return File(path).absoluteFile.parentFile
        val uri = entry.uri ?: return null
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        val relative = try {
            context.contentResolver.query(
                Uri.parse(uri),
                arrayOf(MediaStore.MediaColumns.RELATIVE_PATH),
                null,
                null,
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: Exception) {
            null
        } ?: return null
        return File(Environment.getExternalStorageDirectory(), relative)
    }

    private fun pruneEmptyDirs(start: File?) {
        val root = publicRootDir().absoluteFile
        var dir = start?.absoluteFile ?: return
        while (dir.path != root.path &&
            dir.path.startsWith(root.path + File.separator)
        ) {
            val children = try {
                dir.listFiles()
            } catch (_: Exception) {
                null
            } ?: return
            if (children.isNotEmpty()) return
            if (!dir.delete()) return
            dir = dir.parentFile?.absoluteFile ?: return
        }
    }

    fun exists(context: Context, entry: OfflineEntry): Boolean {
        val uri = entry.uri
        if (!uri.isNullOrBlank()) {
            return try {
                context.contentResolver.openAssetFileDescriptor(Uri.parse(uri), "r")?.use { true }
                    ?: false
            } catch (_: Exception) {
                false
            }
        }
        val path = entry.path ?: return false
        val file = File(path)
        return file.exists() && file.length() > 0
    }

    /** Открывает папку библиотеки в системном проводнике, если он это умеет. */
    fun openFolder(context: Context): Boolean {
        return try {
            val uri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:${rootLabel()}"
            )
            context.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    fun freeSpace(): Long = try {
        // Папки библиотеки может ещё не быть: usableSpace несуществующего
        // пути на старых прошивках возвращает 0, поэтому поднимаемся до
        // ближайшего существующего каталога.
        var dir: File? = rootDir()
        while (dir != null && !dir.exists()) dir = dir.parentFile
        (dir ?: Environment.getExternalStorageDirectory()).usableSpace
    } catch (_: Exception) {
        -1L
    }

    /** `flac-mp4` — FLAC внутри MP4, который мы пересобираем в обычный FLAC. */
    fun isFlacMp4(codec: String?): Boolean =
        codec.orEmpty().contains("flac-mp4", ignoreCase = true)

    /** MIME-тип и расширение по типу потока. */
    fun mimeFor(contentType: String): String = when {
        contentType.contains("flac") -> "audio/flac"
        contentType.contains("mp4") -> "audio/mp4"
        contentType.contains("aac") -> "audio/aac"
        contentType.contains("ogg") -> "audio/ogg"
        else -> "audio/mpeg"
    }

    fun extensionFor(contentType: String): String = when {
        contentType.contains("flac") -> "flac"
        contentType.contains("mp4") -> "m4a"
        contentType.contains("aac") -> "aac"
        contentType.contains("ogg") -> "ogg"
        else -> "mp3"
    }

    /** Тип и расширение с учётом кодека: FLAC-in-MP4 сохраняем как `.flac`. */
    fun mimeFor(codec: String?, contentType: String): String =
        if (isFlacMp4(codec)) "audio/flac" else mimeFor(contentType)

    fun extensionFor(codec: String?, contentType: String): String =
        if (isFlacMp4(codec)) "flac" else extensionFor(contentType)

    fun formatLabel(codec: String?, contentType: String): String {
        val value = codec.orEmpty().lowercase()
        return when {
            value.contains("flac") -> "FLAC"
            value.contains("aac") -> "AAC"
            value.contains("mp3") -> "MP3"
            value.contains("ogg") || value.contains("opus") -> "OGG"
            contentType.contains("flac") -> "FLAC"
            contentType.contains("mp4") -> "M4A"
            contentType.contains("aac") -> "AAC"
            contentType.contains("ogg") -> "OGG"
            else -> "MP3"
        }
    }

    fun formatFromMime(mime: String?, name: String): String {
        val value = (mime ?: "").lowercase()
        val ext = name.substringAfterLast('.', "").lowercase()
        return when {
            value.contains("flac") || ext == "flac" -> "FLAC"
            value.contains("mp4") || ext == "m4a" || ext == "mp4" -> "M4A"
            value.contains("aac") || ext == "aac" -> "AAC"
            value.contains("ogg") || ext == "ogg" -> "OGG"
            value.contains("wav") || ext == "wav" -> "WAV"
            else -> "MP3"
        }
    }

    fun formatFromExtension(name: String): String =
        formatFromMime(null, name)

    private fun mimeTypeForName(name: String): String = when {
        name.endsWith(".flac", true) -> "audio/flac"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".aac", true) -> "audio/aac"
        name.endsWith(".ogg", true) -> "audio/ogg"
        else -> "audio/mpeg"
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private object Api29 {

        fun publish(
            context: Context,
            source: File,
            artist: String,
            album: String,
            fileName: String,
            mime: String
        ): StoredFile? {
            val resolver = context.contentResolver
            val relative = "${Environment.DIRECTORY_MUSIC}/$ROOT_NAME/$artist/$album"
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Audio.Media.MIME_TYPE, mime)
                put(MediaStore.Audio.Media.RELATIVE_PATH, relative)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            val uri = try {
                resolver.insert(collection, values)
            } catch (_: Exception) {
                null
            } ?: return null

            try {
                val stream = resolver.openOutputStream(uri, "w") ?: throw IllegalStateException()
                FileInputStream(source).use { input ->
                    stream.use { output -> input.copyTo(output) }
                }
                val done = ContentValues().apply {
                    put(MediaStore.MediaColumns.IS_PENDING, 0)
                }
                resolver.update(uri, done, null, null)
                val size = try {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: source.length()
                } catch (_: Exception) {
                    source.length()
                }
                return StoredFile(uri.toString(), null, fileName, size)
            } catch (_: Exception) {
                try {
                    resolver.delete(uri, null, null)
                } catch (_: Exception) {
                }
                return null
            }
        }
    }
}
