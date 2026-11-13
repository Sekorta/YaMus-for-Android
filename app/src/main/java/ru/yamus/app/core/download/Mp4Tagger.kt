package ru.yamus.app.core.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * Запись тегов в MP4/M4A без перепаковки аудиоданных.
 *
 * Jaudiotagger умеет читать MP4-атомы, но его `Mp4TagWriter` пересобирает
 * файл и на FLAC-in-MP4 (кодек `flac-mp4` от Яндекса) теряет часть данных:
 * декодер потом ругается на битые FLAC-кадры. Поэтому здесь тег аккуратно
 * добавляется внутрь `moov`, а `mdat` копируется байт в байт; меняются только
 * абсолютные смещения чанков (`stco`/`co64`).
 */
internal object Mp4Tagger {

    private val containers = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "udta", "edts", "dinf", "mvex"
    )

    private class Box(val type: String, val start: Long, val size: Long, val header: Int)

    fun write(
        file: File,
        meta: TagWriter.Metadata,
        cover: ByteArray?,
        coverMime: String?
    ): Boolean {
        if (!file.exists() || file.length() <= 0) return false
        val ext = file.extension.lowercase()
        if (ext != "m4a" && ext != "mp4") return false

        return try {
            val boxes = ArrayList<Box>()
            var moovStart = -1L
            var moov: ByteArray? = null
            RandomAccessFile(file, "r").use { raf ->
                val length = raf.length()
                var offset = 0L
                val header = ByteArray(16)
                while (offset + 8 <= length) {
                    raf.seek(offset)
                    raf.readFully(header, 0, 8)
                    var size = u32(header, 0)
                    val type = String(header, 4, 4, Charsets.ISO_8859_1)
                    var headerSize = 8
                    if (size == 1L) {
                        raf.readFully(header, 8, 8)
                        size = u64(header, 8)
                        headerSize = 16
                    } else if (size == 0L) {
                        size = length - offset
                    }
                    if (size < headerSize || offset + size > length) break
                    boxes.add(Box(type, offset, size, headerSize))
                    if (type == "moov") {
                        val bytes = ByteArray(size.toInt())
                        raf.seek(offset)
                        raf.readFully(bytes)
                        moov = bytes
                        moovStart = offset
                    }
                    offset += size
                }
            }

            val oldMoov = moov ?: return false
            if (moovStart < 0) return false

            val udta = buildUdta(meta, cover, coverMime)
            val newMoov = rebuildMoov(oldMoov, udta)
            val delta = (newMoov.size - oldMoov.size).toLong()
            patchOffsets(newMoov, headerLen(newMoov), newMoov.size, moovStart, delta)

            val temp = File(file.parentFile, file.name + ".tagtmp")
            try {
                temp.delete()
                RandomAccessFile(file, "r").use { input ->
                    RandomAccessFile(temp, "rw").use { output ->
                        output.setLength(0)
                        val buffer = ByteArray(64 * 1024)
                        for (box in boxes) {
                            if (box.start == moovStart) {
                                output.write(newMoov)
                                continue
                            }
                            input.seek(box.start)
                            var left = box.size
                            while (left > 0) {
                                val count = minOf(buffer.size.toLong(), left).toInt()
                                input.readFully(buffer, 0, count)
                                output.write(buffer, 0, count)
                                left -= count
                            }
                        }
                    }
                }
            } catch (_: Throwable) {
                temp.delete()
                return false
            }

            if (!replace(temp, file)) {
                temp.delete()
                return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    private fun replace(temp: File, target: File): Boolean {
        try {
            if (target.delete() && temp.renameTo(target)) return true
        } catch (_: Throwable) {
        }
        return try {
            RandomAccessFile(temp, "r").use { input ->
                RandomAccessFile(target, "rw").use { output ->
                    output.setLength(0)
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count <= 0) break
                        output.write(buffer, 0, count)
                    }
                }
            }
            temp.delete()
            true
        } catch (_: Throwable) {
            false
        }
    }

    // ------------------------------------------------------------------
    // moov
    // ------------------------------------------------------------------

    private fun rebuildMoov(moov: ByteArray, udta: ByteArray): ByteArray {
        val header = headerLen(moov)
        val children = ByteArrayOutputStream()
        var offset = header
        while (offset + 8 <= moov.size) {
            val box = parseBox(moov, offset, moov.size) ?: break
            if (box.type != "udta") {
                children.write(moov, offset, box.size.toInt())
            }
            offset += box.size.toInt()
        }
        val body = children.toByteArray()
        val total = 8 + body.size + udta.size
        val out = ByteArrayOutputStream(total)
        writeU32(out, total.toLong())
        out.write("moov".toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.write(udta)
        return out.toByteArray()
    }

    private fun patchOffsets(
        data: ByteArray,
        start: Int,
        end: Int,
        moovStart: Long,
        delta: Long
    ) {
        var offset = start
        while (offset + 8 <= end) {
            val box = parseBox(data, offset, end) ?: return
            val payload = offset + box.header
            val boxEnd = offset + box.size.toInt()
            when {
                box.type == "stco" -> {
                    val count = u32(data, payload + 4).toInt()
                    var position = payload + 8
                    repeat(count) {
                        if (position + 4 <= boxEnd) {
                            val value = u32(data, position)
                            if (value >= moovStart) writeU32(data, position, value + delta)
                        }
                        position += 4
                    }
                }
                box.type == "co64" -> {
                    val count = u32(data, payload + 4).toInt()
                    var position = payload + 8
                    repeat(count) {
                        if (position + 8 <= boxEnd) {
                            val value = u64(data, position)
                            if (value >= moovStart) writeU64(data, position, value + delta)
                        }
                        position += 8
                    }
                }
                box.type in containers || box.type == "meta" -> {
                    val childStart = payload + if (box.type == "meta") 4 else 0
                    patchOffsets(data, childStart, boxEnd, moovStart, delta)
                }
            }
            offset = boxEnd
        }
    }

    // ------------------------------------------------------------------
    // Теги
    // ------------------------------------------------------------------

    private fun buildUdta(
        meta: TagWriter.Metadata,
        cover: ByteArray?,
        coverMime: String?
    ): ByteArray {
        val ilst = ByteArrayOutputStream()
        meta.title?.let { ilst.write(textItem(ATOM_TITLE, it)) }
        meta.artist?.let { ilst.write(textItem(ATOM_ARTIST, it)) }
        meta.albumArtist?.let { ilst.write(textItem(ATOM_ALBUM_ARTIST, it)) }
        meta.album?.let { ilst.write(textItem(ATOM_ALBUM, it)) }
        meta.genre?.let { ilst.write(textItem(ATOM_GENRE, it)) }
        meta.year?.let { ilst.write(textItem(ATOM_YEAR, it)) }
        meta.comment?.let { ilst.write(textItem(ATOM_COMMENT, it)) }
        if (meta.track > 0) {
            ilst.write(numberItem(ATOM_TRACK, meta.track, meta.trackTotal, true))
        }
        if (meta.disc > 0) {
            ilst.write(numberItem(ATOM_DISC, meta.disc, 0, false))
        }
        if (cover != null && cover.isNotEmpty()) {
            val kind = if (coverMime?.contains("png", true) == true) 14 else 13
            ilst.write(box("covr", dataBox(cover, kind)))
        }

        val metaBox = box("meta", ByteArray(4) + box("ilst", ilst.toByteArray()))
        return box("udta", metaBox)
    }

    private fun textItem(atom: String, value: String): ByteArray =
        box(atom, dataBox(value.toByteArray(Charsets.UTF_8), 1))

    private fun numberItem(atom: String, number: Int, total: Int, track: Boolean): ByteArray {
        val payload = ByteArray(8)
        payload[2] = ((number shr 8) and 0xFF).toByte()
        payload[3] = (number and 0xFF).toByte()
        payload[4] = ((total shr 8) and 0xFF).toByte()
        payload[5] = (total and 0xFF).toByte()
        return box(atom, dataBox(payload, 0))
    }

    private fun dataBox(payload: ByteArray, kind: Int): ByteArray {
        val out = ByteArrayOutputStream(16 + payload.size)
        writeU32(out, kind.toLong())
        writeU32(out, 0)
        out.write(payload)
        return box("data", out.toByteArray())
    }

    private fun box(type: String, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + payload.size)
        writeU32(out, (8 + payload.size).toLong())
        out.write(type.toByteArray(Charsets.ISO_8859_1))
        out.write(payload)
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Атомы
    // ------------------------------------------------------------------

    private fun headerLen(data: ByteArray): Int = if (u32(data, 0) == 1L) 16 else 8

    private fun parseBox(data: ByteArray, offset: Int, end: Int): Box? {
        if (offset + 8 > end) return null
        var size = u32(data, offset)
        val type = String(data, offset + 4, 4, Charsets.ISO_8859_1)
        var header = 8
        if (size == 1L) {
            if (offset + 16 > end) return null
            size = u64(data, offset + 8)
            header = 16
        } else if (size == 0L) {
            size = (end - offset).toLong()
        }
        if (size < header || offset + size > end) return null
        return Box(type, offset.toLong(), size, header)
    }

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun u64(data: ByteArray, offset: Int): Long =
        (u32(data, offset) shl 32) or u32(data, offset + 4)

    private fun writeU32(data: ByteArray, offset: Int, value: Long) {
        data[offset] = (value ushr 24).toByte()
        data[offset + 1] = (value ushr 16).toByte()
        data[offset + 2] = (value ushr 8).toByte()
        data[offset + 3] = value.toByte()
    }

    private fun writeU64(data: ByteArray, offset: Int, value: Long) {
        var v = value
        for (i in 7 downTo 0) {
            data[offset + i] = (v and 0xFF).toByte()
            v = v ushr 8
        }
    }

    private fun writeU32(out: ByteArrayOutputStream, value: Long) {
        out.write((value ushr 24).toInt() and 0xFF)
        out.write((value ushr 16).toInt() and 0xFF)
        out.write((value ushr 8).toInt() and 0xFF)
        out.write(value.toInt() and 0xFF)
    }

    private const val ATOM_TITLE = "\u00a9nam"
    private const val ATOM_ARTIST = "\u00a9ART"
    private const val ATOM_ALBUM_ARTIST = "aART"
    private const val ATOM_ALBUM = "\u00a9alb"
    private const val ATOM_GENRE = "\u00a9gen"
    private const val ATOM_YEAR = "\u00a9day"
    private const val ATOM_COMMENT = "\u00a9cmt"
    private const val ATOM_TRACK = "trkn"
    private const val ATOM_DISC = "disk"
}
