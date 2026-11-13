package ru.yamus.app.core.download

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.models.Album
import ru.yamus.app.core.models.Artist
import ru.yamus.app.core.models.Track
import ru.yamus.app.core.models.TrackPosition
import ru.yamus.app.core.player.AudioFetcher
import ru.yamus.app.core.player.StreamProxy
import ru.yamus.app.core.player.TrackUrlResolver
import ru.yamus.app.core.util.AppScope
import ru.yamus.app.core.util.artistText
import ru.yamus.app.core.util.coverUrl
import ru.yamus.app.core.util.displayTitle
import ru.yamus.app.core.util.mainAlbum
import ru.yamus.app.core.util.mainAlbumId
import java.io.File
import java.util.ArrayDeque
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Пользовательская офлайн-библиотека «YaMus Library».
 *
 * Отвечает за скачивание, индекс, обложки из тегов, удаление и поиск
 * локального файла для плеера. Индекс — только ускоритель: после
 * переустановки он собирается заново по файлам и их тегам. Поэтому
 * скачанная музыка не теряется ни при очистке кэша, ни при выходе из
 * аккаунта, ни при переустановке приложения.
 */
object OfflineLibrary {

    enum class State { NONE, QUEUED, DOWNLOADING, DONE, FAILED }

    interface Listener {
        fun onLibraryChanged()
    }

    private enum class EnqueueResult { STARTED, ALREADY, NEED_PERMISSION, NO_NETWORK, NO_SPACE, FAILED }

    private data class Job(
        val track: Track,
        val source: StreamProxy.Source?,
        val manual: Boolean
    )

    private data class IndexFile(
        val version: Int = 1,
        val entries: List<OfflineEntry> = emptyList()
    )

    private const val INDEX_NAME = "offline-library.json"
    private const val MAX_AUTO_ATTEMPTS = 500

    private val lock = Any()
    private val handlers = CopyOnWriteArrayList<Listener>()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val gson = Gson()

    private val entries = ArrayList<OfflineEntry>()
    private val jobs = ArrayDeque<Job>()
    private val activeKeys = HashSet<String>()
    private val failedKeys = HashSet<String>()
    private val attempted = HashSet<String>()
    private val coverRequests = HashSet<String>()

    private var appContext: Context? = null
    private var loaded = false
    private var worker: kotlinx.coroutines.Job? = null
    private var activeKey: String? = null

    fun init(context: Context) {
        if (appContext != null) return
        appContext = context.applicationContext
        AppScope.launch {
            withContext(Dispatchers.IO) { load() }
        }
    }

    fun addListener(listener: Listener) {
        handlers.addIfAbsent(listener)
    }

    fun removeListener(listener: Listener) {
        handlers.remove(listener)
    }

    // ------------------------------------------------------------------
    // Чтение состояния
    // ------------------------------------------------------------------

    fun entries(): List<OfflineEntry> = synchronized(lock) {
        entries.sortedWith(
            compareBy<OfflineEntry, String>(String.CASE_INSENSITIVE_ORDER) { it.artist }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.album }
                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.title }
        )
    }

    fun hasTracks(): Boolean = synchronized(lock) { entries.isNotEmpty() }

    fun isDownloading(): Boolean = synchronized(lock) {
        activeKey != null || jobs.isNotEmpty()
    }

    fun totalSize(): Long = synchronized(lock) { entries.sumOf { it.size } }

    fun find(track: Track): OfflineEntry? = synchronized(lock) { findLocked(track) }

    fun findById(trackId: String): OfflineEntry? = synchronized(lock) {
        entries.firstOrNull { it.trackId == trackId }
    }

    fun stateOf(track: Track): State {
        synchronized(lock) {
            if (findLocked(track) != null) return State.DONE
            val key = matchKey(track)
            if (activeKey == key) return State.DOWNLOADING
            if (activeKeys.contains(key)) return State.QUEUED
            if (failedKeys.contains(key)) return State.FAILED
        }
        return State.NONE
    }

    fun formatOf(track: Track): String? = find(track)?.format?.takeIf { it.isNotBlank() }

    fun trackEntry(track: Track): Track? = find(track)?.let { trackFromEntry(it) }

    fun trackFromEntry(entry: OfflineEntry): Track {
        val album = entry.album.takeIf { it.isNotBlank() }
        val title = entry.title.ifBlank {
            entry.displayName.substringBeforeLast('.')
                .substringAfter(" - ", entry.displayName.substringBeforeLast('.'))
        }
        return Track(
            id = entry.trackId,
            title = title,
            durationMs = entry.durationMs,
            artists = listOf(Artist(id = 0, name = entry.artist.ifBlank { "Неизвестный исполнитель" })),
            albums = listOf(
                Album(
                    id = entry.albumId,
                    title = album ?: "Синглы",
                    year = entry.year.takeIf { it > 0 },
                    genre = entry.genre,
                    trackPosition = TrackPosition(
                        volume = entry.discNumber.coerceAtLeast(1),
                        index = entry.trackNumber.coerceAtLeast(0)
                    )
                )
            )
        )
    }

    /** Библиотека могла быть создана раньше, но индекс ещё не прочитан. */
    fun libraryMayExist(context: Context): Boolean {
        if (hasTracks()) return true
        if (indexFile(context).exists()) return true
        // На Android 10+ папка библиотеки лежит в общем MediaStore, и без
        // разрешения на аудио проверить её нельзя. Кнопку показываем всегда:
        // экран «Скачанные треки» сам попросит доступ и покажет пустое
        // состояние, если скачанного действительно нет.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return true
        return try {
            hasAudioFiles(LibraryStorage.rootDir())
        } catch (_: Exception) {
            false
        }
    }

    private fun hasAudioFiles(dir: File, depth: Int = 0): Boolean {
        if (depth > 4) return false
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return false
        for (child in children) {
            if (child.isDirectory) {
                if (hasAudioFiles(child, depth + 1)) return true
            } else if (child.length() > 0 && isAudioName(child.name)) {
                return true
            }
        }
        return false
    }

    // ------------------------------------------------------------------
    // Скачивание
    // ------------------------------------------------------------------

    /** Ручное скачивание из меню трека. Возвращает false, если начать нельзя. */
    fun downloadNow(context: Context, track: Track): Boolean {
        if (TokenStorage.token.isNullOrEmpty()) {
            toast("Сначала войдите в аккаунт")
            return false
        }
        if (track.id.isBlank()) return false
        if (find(track) != null) {
            toast("Трек уже скачан")
            return false
        }
        if (needsWritePermission(context)) {
            toast("Разрешите доступ к памяти, чтобы сохранять музыку")
            return false
        }
        synchronized(lock) { failedKeys.remove(matchKey(track)) }
        enqueue(context, track, null, manual = true)
        return true
    }

    /** Автоскачивание уже играющего трека. Не скачивает очередь целиком. */
    fun autoDownload(
        context: Context,
        track: Track,
        source: StreamProxy.Source?,
        fromLikes: Boolean = false
    ) {
        if (!TokenStorage.autoDownload) return
        // Пользователь может ограничить автоскачивание только треками,
        // которые играют из «Мне нравится»: остальные списки не трогаем.
        if (TokenStorage.autoDownloadLikedOnly && !fromLikes) return
        if (track.id.isBlank()) return
        if (find(track) != null) return
        if (needsWritePermission(context)) return
        if (TokenStorage.wifiOnlyDownloads && !isWifi(context)) return
        val key = matchKey(track)
        synchronized(lock) {
            if (attempted.size >= MAX_AUTO_ATTEMPTS) attempted.clear()
            if (!attempted.add(key)) return
            if (failedKeys.contains(key)) return
        }
        enqueue(context, track, source, manual = false)
    }

    private fun enqueue(
        context: Context,
        track: Track,
        source: StreamProxy.Source?,
        manual: Boolean
    ) {
        val key = matchKey(track)
        synchronized(lock) {
            if (findLocked(track) != null) return
            if (activeKeys.contains(key)) return
            if (LibraryStorage.freeSpace() in 1..(32L * 1024L * 1024L)) {
                mainHandler.post { toast("Недостаточно свободного места") }
                return
            }
            activeKeys.add(key)
            jobs.addLast(Job(track, source, manual))
        }
        notifyChanged()
        startWorker(context.applicationContext)
    }

    private fun startWorker(context: Context) {
        if (worker?.isActive == true) return
        worker = AppScope.launch {
            withContext(Dispatchers.IO) { drain(context) }
        }
    }

    private suspend fun drain(context: Context) {
        while (true) {
            val job = synchronized(lock) { jobs.pollFirst() } ?: break
            val key = matchKey(job.track)
            synchronized(lock) { activeKey = key }
            notifyChanged()
            try {
                process(context, job)
            } catch (_: Exception) {
                synchronized(lock) { failedKeys.add(key) }
            } finally {
                synchronized(lock) {
                    activeKeys.remove(key)
                    if (activeKey == key) activeKey = null
                }
                notifyChanged()
            }
        }
    }

    private suspend fun process(context: Context, job: Job) {
        val track = job.track
        val key = matchKey(track)
        val source = job.source ?: TrackUrlResolver.resolve(track).let { resolved ->
            if (resolved == null) {
                synchronized(lock) { failedKeys.add(key) }
                return
            }
            resolved
        }
        val artist = safeSegment(artistFolder(track), 60, "Неизвестный исполнитель")
        val album = safeSegment(albumFolder(track), 60, "Синглы")
        val tempDir = File(context.cacheDir, "offline-tmp")
        if (!tempDir.exists() && !tempDir.mkdirs()) {
            synchronized(lock) { failedKeys.add(key) }
            return
        }
        val base = safeSegment(track.id, 40, "track")
        val temp = File(tempDir, "$base.download")
        var converted: File? = null
        var mime = LibraryStorage.mimeFor(source.contentType)
        var ext = LibraryStorage.extensionFor(source.contentType)
        var codec = source.codec
        try {
            temp.delete()
            if (AudioFetcher.fetch(source, temp) <= 0) {
                synchronized(lock) { failedKeys.add(key) }
                return
            }

            // Яндекс отдаёт lossless как `flac-mp4`: FLAC в MP4-контейнере.
            // Пересобираем его в настоящий .flac, иначе в библиотеке
            // появлялся бы файл с расширением .m4a.
            if (LibraryStorage.isFlacMp4(source.codec)) {
                val flac = File(tempDir, "$base.flac")
                flac.delete()
                if (FlacMp4Extractor.extract(temp, flac)) {
                    temp.delete()
                    converted = flac
                    mime = "audio/flac"
                    ext = "flac"
                    codec = "flac"
                } else {
                    flac.delete()
                }
            }

            val output = converted ?: temp
            val fileName = uniqueFileName(context, track, artist, ext)
            val cover = CoverFetcher.fetch(track)
            // Теги пишем во временный файл: в MediaStore нельзя открыть
            // content:// как RandomAccessFile для ID3/FLAC.
            TagWriter.write(output, track, cover?.first, cover?.second)

            val stored = LibraryStorage.publish(
                context, output, artist, album, fileName, mime
            )
            if (stored == null) {
                synchronized(lock) { failedKeys.add(key) }
                return
            }
            if (cover != null) saveCover(context, track.id, cover.first)

            val albumInfo = track.mainAlbum()
            val entry = OfflineEntry(
                trackId = track.id,
                uri = stored.uri,
                path = stored.path,
                displayName = stored.displayName,
                title = track.title.orEmpty(),
                artist = track.artists.orEmpty()
                    .mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }
                    .joinToString(", ")
                    .ifBlank { "Неизвестный исполнитель" },
                album = albumInfo?.title.orEmpty(),
                albumId = track.mainAlbumId(),
                year = albumInfo?.year ?: 0,
                trackNumber = albumInfo?.trackPosition?.index ?: 0,
                discNumber = albumInfo?.trackPosition?.volume ?: 1,
                genre = albumInfo?.genre,
                durationMs = track.durationMs,
                format = LibraryStorage.formatLabel(codec, mime),
                mime = mime,
                size = stored.size,
                addedAt = System.currentTimeMillis()
            )
            synchronized(lock) {
                entries.removeAll { it.trackId == entry.trackId }
                entries.add(entry)
                failedKeys.remove(key)
            }
            saveIndex(context)
        } finally {
            temp.delete()
            converted?.delete()
        }
    }

    // ------------------------------------------------------------------
    // Удаление
    // ------------------------------------------------------------------

    fun remove(context: Context, track: Track) {
        val entry = find(track) ?: return
        AppScope.launch {
            withContext(Dispatchers.IO) {
                LibraryStorage.delete(context, entry)
                synchronized(lock) { entries.remove(entry) }
                deleteCover(context, entry.trackId)
                saveIndex(context)
            }
            notifyChanged()
        }
    }

    fun removeEntry(context: Context, entry: OfflineEntry) {
        AppScope.launch {
            withContext(Dispatchers.IO) {
                LibraryStorage.delete(context, entry)
                synchronized(lock) { entries.remove(entry) }
                deleteCover(context, entry.trackId)
                saveIndex(context)
            }
            notifyChanged()
        }
    }

    fun clearAll(context: Context) {
        AppScope.launch {
            val snapshot = synchronized(lock) { entries.toList() }
            withContext(Dispatchers.IO) {
                snapshot.forEach {
                    LibraryStorage.delete(context, it)
                    deleteCover(context, it.trackId)
                }
                synchronized(lock) {
                    entries.clear()
                    failedKeys.clear()
                }
                saveIndex(context)
            }
            notifyChanged()
        }
    }

    /** Файл перестал открываться: убираем запись, плеер сыграет из сети. */
    fun forget(context: Context, track: Track) {
        val entry = find(track) ?: return
        synchronized(lock) { entries.remove(entry) }
        AppScope.launch {
            withContext(Dispatchers.IO) { saveIndex(context) }
            notifyChanged()
        }
    }

    // ------------------------------------------------------------------
    // Пересканирование (после переустановки или ручных изменений)
    // ------------------------------------------------------------------

    /**
     * Собирает индекс заново по файлам. Возвращает true, если сканирование
     * было разрешено и данные можно считать актуальными.
     */
    suspend fun refresh(context: Context): Boolean = withContext(Dispatchers.IO) {
        val scanned = when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                if (needsReadPermission(context)) return@withContext false
                MediaStoreScan.scan(context)
            }
            else -> {
                if (needsReadPermission(context)) return@withContext false
                scanFiles()
            }
        } ?: return@withContext false

        val merged = merge(scanned)
        synchronized(lock) {
            entries.clear()
            entries.addAll(merged)
            failedKeys.clear()
        }
        saveIndex(context)
        notifyChanged()
        true
    }

    private fun scanFiles(): List<OfflineEntry>? {
        val root = LibraryStorage.rootDir()
        if (!root.exists()) return emptyList()
        val result = ArrayList<OfflineEntry>()
        val files = ArrayList<File>()
        collectAudioFiles(root, files, 0)
        for (file in files) {
            readAudioFile(file)?.let { result.add(it) }
        }
        return result
    }

    private fun collectAudioFiles(dir: File, out: ArrayList<File>, depth: Int) {
        if (depth > 4) return
        val children = try {
            dir.listFiles()
        } catch (_: Exception) {
            null
        } ?: return
        for (child in children) {
            if (child.isDirectory) {
                collectAudioFiles(child, out, depth + 1)
            } else if (child.length() > 0 && isAudioName(child.name)) {
                out.add(child)
            }
        }
    }

    private fun readAudioFile(file: File): OfflineEntry? {
        val name = file.nameWithoutExtension
        var title = name.substringAfter(" - ", name)
        var artist = name.substringBefore(" - ", "")
        var album = file.parentFile?.name.orEmpty()
        var year = 0
        var genre: String? = null
        var trackNumber = 0
        var discNumber = 1
        var duration = 0L
        var trackId = ""

        try {
            val audio = AudioFileIO.read(file)
            val tag = audio.tag
            tag?.getFirst(FieldKey.TITLE)?.takeIf { it.isNotBlank() }?.let { title = it }
            tag?.getFirst(FieldKey.ARTIST)?.takeIf { it.isNotBlank() }?.let { artist = it }
            tag?.getFirst(FieldKey.ALBUM)?.takeIf { it.isNotBlank() }?.let { album = it }
            tag?.getFirst(FieldKey.YEAR)?.take(4)?.toIntOrNull()?.let { year = it }
            tag?.getFirst(FieldKey.GENRE)?.takeIf { it.isNotBlank() }?.let { genre = it }
            tag?.getFirst(FieldKey.TRACK)?.substringBefore('/')?.toIntOrNull()?.let {
                trackNumber = it
            }
            tag?.getFirst(FieldKey.DISC_NO)?.substringBefore('/')?.toIntOrNull()?.let {
                discNumber = it.coerceAtLeast(1)
            }
            trackId = TagWriter.trackIdFromComment(tag?.getFirst(FieldKey.COMMENT)).orEmpty()
            duration = (audio.audioHeader?.trackLength ?: 0).toLong() * 1000L
        } catch (_: Throwable) {
        }

        if (artist.isBlank()) artist = file.parentFile?.parentFile?.name.orEmpty()
        return OfflineEntry(
            trackId = trackId.ifBlank { "offline:" + file.absolutePath.hashCode().toString(16) },
            path = file.absolutePath,
            displayName = file.name,
            title = title,
            artist = artist.ifBlank { "Неизвестный исполнитель" },
            album = album.ifBlank { "Синглы" },
            year = year,
            trackNumber = trackNumber,
            discNumber = discNumber,
            genre = genre,
            durationMs = duration,
            format = LibraryStorage.formatFromExtension(file.name),
            mime = mimeForName(file.name),
            size = file.length(),
            addedAt = file.lastModified()
        )
    }

    private fun merge(scanned: List<OfflineEntry>): List<OfflineEntry> {
        val old = synchronized(lock) { entries.toList() }
        if (old.isEmpty()) return scanned
        val byFile = HashMap<String, OfflineEntry>()
        val byKey = HashMap<String, OfflineEntry>()
        old.forEach {
            byFile[identity(it)] = it
            byKey[matchKey(it)] = it
        }
        return scanned.map { fresh ->
            val previous = byFile[identity(fresh)] ?: byKey[matchKey(fresh)]
            if (previous != null && !previous.trackId.startsWith("offline:")) {
                fresh.copy(trackId = previous.trackId)
            } else {
                fresh
            }
        }
    }

    // ------------------------------------------------------------------
    // Обложки
    // ------------------------------------------------------------------

    fun coverModel(context: Context, track: Track): Any? {
        val entry = find(track)
        if (entry == null) return null
        val cached = coverFile(context, entry.trackId)
        if (cached != null && cached.length() > 0) return cached
        ensureCover(context, track)
        return null
    }

    fun coverFileFor(context: Context, entry: OfflineEntry): File? {
        val cached = coverFile(context, entry.trackId)
        if (cached != null && cached.length() > 0) return cached
        ensureCover(context, trackFromEntry(entry))
        return null
    }

    /**
     * Обложка с ожиданием: если встроенной картинки ещё нет в кэше,
     * извлекает её прямо сейчас. Вызывать только с IO-потока.
     */
    fun coverFileSync(context: Context, entry: OfflineEntry): File? {
        migrateLegacyCover(context, entry.trackId)
        val cached = coverFile(context, entry.trackId)
        if (cached != null && cached.length() > 0) return cached
        val bytes = extractEmbeddedCover(context, entry) ?: return null
        saveCover(context, entry.trackId, bytes)
        return coverFile(context, entry.trackId)?.takeIf { it.length() > 0 }
    }

    fun ensureCover(context: Context, track: Track) {
        val entry = find(track) ?: return
        val key = entry.trackId
        synchronized(lock) { if (!coverRequests.add(key)) return }
        AppScope.launch {
            val extracted = withContext(Dispatchers.IO) {
                migrateLegacyCover(context, entry.trackId)
                val target = coverFile(context, entry.trackId)
                if (target != null && target.length() > 0) return@withContext true
                val bytes = extractEmbeddedCover(context, entry)
                    ?: CoverFetcher.fetch(track)?.first
                    ?: return@withContext false
                saveCover(context, entry.trackId, bytes)
                true
            }
            if (extracted) notifyChanged()
        }
    }

    private fun extractEmbeddedCover(context: Context, entry: OfflineEntry): ByteArray? {
        val retriever = MediaMetadataRetriever()
        return try {
            when {
                !entry.uri.isNullOrBlank() -> retriever.setDataSource(
                    context, Uri.parse(entry.uri)
                )
                !entry.path.isNullOrBlank() -> retriever.setDataSource(entry.path)
                else -> return null
            }
            retriever.embeddedPicture
        } catch (_: Throwable) {
            null
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun coverFile(context: Context, trackId: String): File? {
        // Обложки лежат в filesDir, а не в cacheDir: автоочистка кэша не
        // должна стирать встроенные обложки скачанных треков.
        val dir = File(context.filesDir, "offline-covers")
        return File(dir, safeSegment(trackId, 40, "cover") + ".img")
    }

    private fun legacyCoverFile(context: Context, trackId: String): File =
        File(
            File(context.cacheDir, "offline-covers"),
            safeSegment(trackId, 40, "cover") + ".img"
        )

    /** Переносит обложку из старого кэша в постоянное хранилище. */
    private fun migrateLegacyCover(context: Context, trackId: String) {
        try {
            val source = legacyCoverFile(context, trackId)
            if (!source.exists() || source.length() <= 0) return
            val target = coverFile(context, trackId) ?: return
            if (target.exists() && target.length() > 0) {
                source.delete()
                return
            }
            val parent = target.parentFile ?: return
            if (!parent.exists() && !parent.mkdirs()) return
            if (source.renameTo(target)) return
            source.copyTo(target, overwrite = true)
            source.delete()
        } catch (_: Exception) {
        }
    }

    private fun saveCover(context: Context, trackId: String, bytes: ByteArray) {
        try {
            val target = coverFile(context, trackId) ?: return
            if (target.exists() && target.length() > 0) return
            val parent = target.parentFile ?: return
            if (!parent.exists() && !parent.mkdirs()) return
            val temp = File(parent, target.name + ".tmp")
            temp.writeBytes(bytes)
            if (!temp.renameTo(target)) {
                temp.delete()
            }
        } catch (_: Exception) {
        }
    }

    private fun deleteCover(context: Context, trackId: String) {
        try {
            coverFile(context, trackId)?.delete()
            legacyCoverFile(context, trackId).delete()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Индекс
    // ------------------------------------------------------------------

    private suspend fun load() {
        val context = appContext ?: return
        val indexFile = indexFile(context)
        var restored: List<OfflineEntry> = emptyList()
        if (indexFile.exists()) {
            try {
                val parsed = gson.fromJson(
                    indexFile.readText(),
                    IndexFile::class.java
                )
                restored = parsed?.entries.orEmpty()
            } catch (_: Exception) {
            }
        }

        if (restored.isNotEmpty() && Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            restored = restored.filter { entry ->
                val path = entry.path
                path != null && File(path).exists()
            }
        }

        synchronized(lock) {
            entries.clear()
            entries.addAll(restored)
        }
        loaded = true
        notifyChanged()

        // Индекс пропал (переустановка) — собираем его по файлам. На новых
        // Android для этого нужно разрешение на чтение аудио, его запросит
        // экран «Скачанные треки».
        if (restored.isEmpty()) {
            try {
                refresh(context)
            } catch (_: Exception) {
            }
        }
    }

    private fun indexFile(context: Context): File = File(context.filesDir, INDEX_NAME)

    private fun saveIndex(context: Context) {
        try {
            val snapshot = synchronized(lock) { entries.toList() }
            val target = indexFile(context)
            val temp = File(context.filesDir, "$INDEX_NAME.tmp")
            temp.writeText(gson.toJson(IndexFile(entries = snapshot)))
            if (!temp.renameTo(target)) {
                target.delete()
                temp.renameTo(target)
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------------
    // Служебное
    // ------------------------------------------------------------------

    fun needsWritePermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        return ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun needsReadPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return false
        val permission = if (Build.VERSION.SDK_INT >= 33) {
            "android.permission.READ_MEDIA_AUDIO"
        } else {
            android.Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return ContextCompat.checkSelfPermission(
            context, permission
        ) != android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    fun readPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) "android.permission.READ_MEDIA_AUDIO"
        else android.Manifest.permission.READ_EXTERNAL_STORAGE

    private fun findLocked(track: Track): OfflineEntry? {
        if (track.id.isNotBlank()) {
            entries.firstOrNull { it.trackId == track.id }?.let { return it }
        }
        val key = matchKey(track)
        entries.firstOrNull { matchKey(it) == key }?.let { return it }
        // Альбом мог быть переименован: сверяемся только по артисту и названию.
        val loose = looseKey(track)
        return entries.firstOrNull { looseKey(it) == loose }
    }

    private fun matchKey(track: Track): String =
        normalize(track.artistText()) + "|" + normalize(track.displayTitle()) + "|" +
            normalize(track.mainAlbum()?.title.orEmpty())

    private fun looseKey(track: Track): String =
        normalize(track.artistText()) + "|" + normalize(track.displayTitle())

    private fun matchKey(entry: OfflineEntry): String =
        normalize(entry.artist) + "|" + normalize(entry.title) + "|" + normalize(entry.album)

    private fun looseKey(entry: OfflineEntry): String =
        normalize(entry.artist) + "|" + normalize(entry.title)

    private fun identity(entry: OfflineEntry): String =
        entry.uri ?: entry.path ?: matchKey(entry)

    private fun normalize(value: String?): String {
        if (value.isNullOrBlank()) return ""
        val builder = StringBuilder(value.length)
        var lastSpace = false
        for (char in value.lowercase()) {
            if (char.isLetterOrDigit()) {
                builder.append(char)
                lastSpace = false
            } else if (!lastSpace && builder.isNotEmpty()) {
                builder.append(' ')
                lastSpace = true
            }
        }
        return builder.toString().trim()
    }

    private fun artistFolder(track: Track): String {
        val first = track.artists.orEmpty()
            .mapNotNull { it.name?.takeIf { name -> name.isNotBlank() } }
            .firstOrNull()
        return first ?: "Неизвестный исполнитель"
    }

    private fun albumFolder(track: Track): String =
        track.mainAlbum()?.title?.takeIf { it.isNotBlank() } ?: "Синглы"

    private fun uniqueFileName(context: Context, track: Track, artist: String, ext: String): String {
        val base = safeSegment(
            "$artist - ${track.displayTitle()}",
            120,
            "track"
        )
        val used = synchronized(lock) {
            entries.map { it.displayName.lowercase() }.toHashSet()
        }
        var name = "$base.$ext"
        var counter = 2
        while (used.contains(name.lowercase()) || legacyFileExists(artist, name, track, ext)) {
            name = "$base ($counter).$ext"
            counter++
        }
        return name
    }

    private fun legacyFileExists(artist: String, name: String, track: Track, ext: String): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return false
        val album = safeSegment(albumFolder(track), 60, "Синглы")
        return File(File(File(LibraryStorage.rootDir(), safeSegment(artist, 60, "Artist")), album), name)
            .exists()
    }

    private fun safeSegment(value: String?, maxLength: Int, fallback: String): String {
        var text = value.orEmpty()
            .replace('/', '_')
            .replace('\\', '_')
            .replace(':', '_')
            .replace('*', '_')
            .replace('?', '_')
            .replace('"', '_')
            .replace('<', '_')
            .replace('>', '_')
            .replace('|', '_')
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('\t', ' ')
        while (text.contains("  ")) text = text.replace("  ", " ")
        text = text.trim().trim('.')
        if (text.length > maxLength) text = text.substring(0, maxLength).trim().trim('.')
        if (text.isEmpty()) text = fallback
        val reserved = text.substringBefore('.').uppercase()
        if (reserved in RESERVED_NAMES) text = "_$text"
        return text
    }

    private fun isWifi(context: Context): Boolean {
        return try {
            val manager =
                context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                    ?: return true
            @Suppress("DEPRECATION")
            val info = manager.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            info.type == ConnectivityManager.TYPE_WIFI ||
                info.type == ConnectivityManager.TYPE_ETHERNET
        } catch (_: Exception) {
            true
        }
    }

    private fun isAudioName(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase()
        return ext in AUDIO_EXTENSIONS
    }

    private fun mimeForName(name: String): String = when {
        name.endsWith(".flac", true) -> "audio/flac"
        name.endsWith(".m4a", true) -> "audio/mp4"
        name.endsWith(".aac", true) -> "audio/aac"
        name.endsWith(".ogg", true) -> "audio/ogg"
        name.endsWith(".wav", true) -> "audio/wav"
        else -> "audio/mpeg"
    }

    private fun toast(message: String) {
        mainHandler.post {
            try {
                android.widget.Toast.makeText(
                    appContext,
                    message,
                    android.widget.Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {
            }
        }
    }

    private fun notifyChanged() {
        val dispatch = {
            for (listener in handlers) {
                try {
                    listener.onLibraryChanged()
                } catch (_: Exception) {
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) dispatch() else mainHandler.post(dispatch)
    }

    // ------------------------------------------------------------------
    // Сканирование MediaStore (Android 10+)
    // ------------------------------------------------------------------

    @RequiresApi(Build.VERSION_CODES.Q)
    private object MediaStoreScan {

        fun scan(context: Context): List<OfflineEntry>? {
            val resolver = context.contentResolver
            val projection = arrayListOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.DISPLAY_NAME,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.MIME_TYPE,
                MediaStore.Audio.Media.SIZE,
                MediaStore.Audio.Media.DATE_ADDED,
                MediaStore.Audio.Media.YEAR,
                MediaStore.Audio.Media.TRACK,
                MediaStore.MediaColumns.RELATIVE_PATH
            )
            // Колонка GENRE появилась только в Android 11: на Android 10
            // запрос с ней падает целиком, и библиотека не находится.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                projection.add(MediaStore.Audio.Media.GENRE)
            }
            val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ?"
            val args = arrayOf("${LibraryStorage.relativeRoot()}/%")
            val cursor = try {
                resolver.query(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                    projection.toTypedArray(),
                    selection,
                    args,
                    null
                )
            } catch (_: Exception) {
                null
            } ?: return null

            val result = ArrayList<OfflineEntry>()
            cursor.use { c ->
                val idIndex = c.getColumnIndex(MediaStore.Audio.Media._ID)
                val nameIndex = c.getColumnIndex(MediaStore.Audio.Media.DISPLAY_NAME)
                val titleIndex = c.getColumnIndex(MediaStore.Audio.Media.TITLE)
                val artistIndex = c.getColumnIndex(MediaStore.Audio.Media.ARTIST)
                val albumIndex = c.getColumnIndex(MediaStore.Audio.Media.ALBUM)
                val albumIdIndex = c.getColumnIndex(MediaStore.Audio.Media.ALBUM_ID)
                val durationIndex = c.getColumnIndex(MediaStore.Audio.Media.DURATION)
                val mimeIndex = c.getColumnIndex(MediaStore.Audio.Media.MIME_TYPE)
                val sizeIndex = c.getColumnIndex(MediaStore.Audio.Media.SIZE)
                val dateIndex = c.getColumnIndex(MediaStore.Audio.Media.DATE_ADDED)
                val yearIndex = c.getColumnIndex(MediaStore.Audio.Media.YEAR)
                val genreIndex = c.getColumnIndex(MediaStore.Audio.Media.GENRE)
                val trackIndex = c.getColumnIndex(MediaStore.Audio.Media.TRACK)

                while (c.moveToNext()) {
                    val id = c.getLong(idIndex)
                    val name = c.getString(nameIndex) ?: continue
                    val mime = c.getString(mimeIndex)
                    val trackRaw = if (trackIndex >= 0) c.getInt(trackIndex) else 0
                    val uri = android.content.ContentUris.withAppendedId(
                        MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                        id
                    ).toString()
                    result.add(
                        OfflineEntry(
                            trackId = "offline:$id",
                            uri = uri,
                            displayName = name,
                            title = (c.getString(titleIndex) ?: name.substringAfterLast('.')).orEmpty(),
                            artist = (c.getString(artistIndex) ?: "Неизвестный исполнитель").orEmpty(),
                            album = (c.getString(albumIndex) ?: "Синглы").orEmpty(),
                            albumId = if (albumIdIndex >= 0) c.getLong(albumIdIndex) else 0L,
                            year = if (yearIndex >= 0) c.getInt(yearIndex) else 0,
                            trackNumber = trackRaw % 1000,
                            discNumber = (trackRaw / 1000).coerceAtLeast(1),
                            genre = if (genreIndex >= 0) c.getString(genreIndex) else null,
                            durationMs = if (durationIndex >= 0) c.getLong(durationIndex) else 0L,
                            format = formatFor(context, mime, name, uri),
                            mime = mime ?: mimeForNameStatic(name),
                            size = if (sizeIndex >= 0) c.getLong(sizeIndex) else 0L,
                            addedAt = if (dateIndex >= 0) c.getLong(dateIndex) * 1000L else 0L
                        )
                    )
                }
            }
            return result
        }

        private fun mimeForNameStatic(name: String): String = when {
            name.endsWith(".flac", true) -> "audio/flac"
            name.endsWith(".m4a", true) -> "audio/mp4"
            name.endsWith(".aac", true) -> "audio/aac"
            else -> "audio/mpeg"
        }

        /**
         * MediaStore показывает FLAC-in-MP4 (`flac-mp4` от Яндекса) как
         * обычный `audio/mp4`, поэтому после переустановки или пересканирования
         * библиотеки трек терял метку FLAC. Достаём кодек из контейнера.
         */
        private fun formatFor(context: Context, mime: String?, name: String, uri: String): String {
            val label = LibraryStorage.formatFromMime(mime, name)
            if (label != "M4A") return label
            return if (isFlacInMp4(context, uri)) "FLAC" else label
        }

        private fun isFlacInMp4(context: Context, uri: String): Boolean {
            // MediaExtractor появился только в API 16, а minSdk приложения — 14.
            // Поэтому обращаемся к нему через reflection: на старых Android
            // класс не должен загружаться даже при верификации.
            var extractor: Any? = null
            return try {
                val extractorClass = Class.forName("android.media.MediaExtractor")
                extractor = extractorClass.getDeclaredConstructor().newInstance()
                extractorClass.getMethod(
                    "setDataSource",
                    Context::class.java,
                    Uri::class.java,
                    Map::class.java
                ).invoke(extractor, context, Uri.parse(uri), null)

                val count = extractorClass.getMethod("getTrackCount")
                    .invoke(extractor) as Int
                val getTrackFormat = extractorClass.getMethod(
                    "getTrackFormat",
                    Int::class.javaPrimitiveType
                )
                val formatClass = Class.forName("android.media.MediaFormat")
                val getString = formatClass.getMethod("getString", String::class.java)

                for (index in 0 until count) {
                    val format = getTrackFormat.invoke(extractor, index) ?: continue
                    val mime = getString.invoke(format, "mime") as? String
                    if (mime?.equals("audio/flac", ignoreCase = true) == true) {
                        return true
                    }
                }
                false
            } catch (_: Throwable) {
                false
            } finally {
                if (extractor != null) {
                    try {
                        extractor.javaClass.getMethod("release").invoke(extractor)
                    } catch (_: Throwable) {
                    }
                }
            }
        }
    }

    private class CoverFetcher {

        companion object {
            private const val MAX_BYTES = 4 * 1024 * 1024
            private val cache = Collections.synchronizedMap(HashMap<String, Pair<ByteArray, String>>())

            fun fetch(track: Track): Pair<ByteArray, String>? {
                val key = track.id
                cache[key]?.let { return it }
                val url = track.coverUrl(600) ?: track.coverUrl(400) ?: return null
                return try {
                    val request = Request.Builder().url(url).build()
                    ApiClient.http.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) return null
                        val body = response.body() ?: return null
                        if (body.contentLength() > MAX_BYTES) return null
                        val bytes = body.bytes()
                        if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
                        val mime = body.contentType()?.toString()?.substringBefore(';')
                            ?: "image/jpeg"
                        val result = bytes to mime
                        if (cache.size > 8) cache.clear()
                        cache[key] = result
                        result
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    private val AUDIO_EXTENSIONS = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav")
    private val RESERVED_NAMES = setOf(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"
    )
}
