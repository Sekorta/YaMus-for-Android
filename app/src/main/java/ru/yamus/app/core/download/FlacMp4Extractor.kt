package ru.yamus.app.core.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * Достаёт FLAC из MP4-контейнера (`flac-mp4` от Яндекса) в обычный `.flac`.
 *
 * В таком файле аудиокадры лежат в `mdat`, а параметры потока — в
 * `moov/trak/mdia/minf/stbl/stsd/fLaC/dfLa` (блок STREAMINFO). Собираем
 * файл заново: сигнатура `fLaC`, STREAMINFO и все кадры по таблицам
 * `stsz`/`stsc`/`stco`/`co64`. Смещения из таблиц читаем как есть, поэтому
 * служебные `moof`-вставки внутри `mdat` не попадают в результат.
 *
 * Если структура не похожа на FLAC-in-MP4 или таблицы противоречат друг
 * другу, метод возвращает false: вызывающий код оставит исходный файл.
 */
internal object FlacMp4Extractor {

    private const val MAX_MOOV = 64L * 1024L * 1024L
    private const val MAX_SAMPLE = 16L * 1024L * 1024L
    private val CONTAINERS = setOf(
        "moov", "trak", "mdia", "minf", "stbl", "udta", "edts", "dinf", "mvex"
    )

    private class Box(val type: String, val start: Int, val size: Int, val header: Int)

    fun extract(source: File, target: File): Boolean {
        if (!source.exists() || source.length() <= 0) return false
        target.delete()
        return try {
            val moov = readMoov(source) ?: return false
            val tables = readTables(moov) ?: return false
            writeFlac(source, target, tables)
        } catch (_: Throwable) {
            target.delete()
            false
        }
    }

    // ------------------------------------------------------------------
    // moov
    // ------------------------------------------------------------------

    private class Tables(
        val streamInfo: ByteArray,
        val sampleSizes: IntArray,
        val chunks: LongArray,
        val samplesPerChunk: IntArray
    )

    private fun readMoov(file: File): ByteArray? {
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
                if (size < headerSize || offset + size > length) return null
                if (type == "moov") {
                    if (size > MAX_MOOV || size > Int.MAX_VALUE) return null
                    val bytes = ByteArray(size.toInt())
                    raf.seek(offset)
                    raf.readFully(bytes)
                    return bytes
                }
                offset += size
            }
        }
        return null
    }

    private fun readTables(moov: ByteArray): Tables? {
        val top = boxes(moov, headerLen(moov), moov.size)
        for (trak in top) {
            if (trak.type != "trak") continue
            val mdia = child(moov, trak, "mdia") ?: continue
            val minf = child(moov, mdia, "minf") ?: continue
            val stbl = child(moov, minf, "stbl") ?: continue
            val stsd = child(moov, stbl, "stsd") ?: continue
            val flac = flacSampleEntry(moov, stsd) ?: continue
            val dfLa = audioSampleChild(moov, flac, "dfLa") ?: continue
            val streamInfo = readStreamInfo(moov, dfLa) ?: continue
            val sizes = readSampleSizes(moov, child(moov, stbl, "stsz")) ?: continue
            val stsc = readStsc(moov, child(moov, stbl, "stsc")) ?: continue
            val chunks = readChunkOffsets(
                moov,
                child(moov, stbl, "stco"),
                child(moov, stbl, "co64")
            ) ?: continue
            val perChunk = expandStsc(stsc, chunks.size, sizes.size) ?: continue
            return Tables(streamInfo, sizes, chunks, perChunk)
        }
        return null
    }

    private fun flacSampleEntry(data: ByteArray, stsd: Box): Box? {
        val payload = stsd.start + stsd.header
        if (payload + 8 > stsd.start + stsd.size) return null
        val count = u32(data, payload + 4).toInt()
        var offset = payload + 8
        repeat(count) {
            val box = parseBox(data, offset, stsd.start + stsd.size) ?: return null
            if (box.type == "fLaC") return box
            offset = box.start + box.size
        }
        return null
    }

    private fun audioSampleChild(data: ByteArray, entry: Box, type: String): Box? {
        val payload = entry.start + entry.header
        if (payload + 28 > entry.start + entry.size) return null
        // AudioSampleEntry: 8 байт служебных + 20 байт полей версии 0.
        val version = u16(data, payload + 8)
        val childStart = payload + 28 + if (version == 1) 16 else 0
        if (childStart >= entry.start + entry.size) return null
        return boxes(data, childStart, entry.start + entry.size)
            .firstOrNull { it.type == type }
    }

    private fun readStreamInfo(data: ByteArray, dfLa: Box): ByteArray? {
        var offset = dfLa.start + dfLa.header + 4
        val end = dfLa.start + dfLa.size
        while (offset + 4 <= end) {
            val header = data[offset].toInt() and 0xFF
            val type = header and 0x7F
            val length = ((data[offset + 1].toInt() and 0xFF) shl 16) or
                ((data[offset + 2].toInt() and 0xFF) shl 8) or
                (data[offset + 3].toInt() and 0xFF)
            if (offset + 4 + length > end) return null
            if (type == 0 && length >= 34) {
                val block = data.copyOfRange(offset, offset + 4 + length)
                // STREAMINFO должен быть последним блоком: после него идут
                // аудиокадры, а не другие метаданные.
                block[0] = (block[0].toInt() or 0x80).toByte()
                return block
            }
            if (header and 0x80 != 0) return null
            offset += 4 + length
        }
        return null
    }

    private fun readSampleSizes(data: ByteArray, box: Box?): IntArray? {
        box ?: return null
        val payload = box.start + box.header
        val end = box.start + box.size
        if (payload + 12 > end) return null
        val fixed = u32(data, payload + 4).toInt()
        val count = u32(data, payload + 8).toInt()
        if (count <= 0 || count > 10_000_000) return null
        if (fixed > 0) return IntArray(count) { fixed }
        if (payload + 12 + count * 4 > end) return null
        return IntArray(count) { index ->
            u32(data, payload + 12 + index * 4).toInt()
        }
    }

    private class StscEntry(val firstChunk: Int, val samplesPerChunk: Int)

    private fun readStsc(data: ByteArray, box: Box?): List<StscEntry>? {
        box ?: return null
        val payload = box.start + box.header
        val end = box.start + box.size
        if (payload + 8 > end) return null
        val count = u32(data, payload + 4).toInt()
        if (count <= 0 || count > 1_000_000) return null
        if (payload + 8 + count * 12 > end) return null
        return List(count) { index ->
            val offset = payload + 8 + index * 12
            StscEntry(
                firstChunk = u32(data, offset).toInt(),
                samplesPerChunk = u32(data, offset + 4).toInt()
            )
        }
    }

    private fun readChunkOffsets(data: ByteArray, stco: Box?, co64: Box?): LongArray? {
        val box = stco ?: co64 ?: return null
        val wide = box === co64
        val payload = box.start + box.header
        val end = box.start + box.size
        if (payload + 8 > end) return null
        val count = u32(data, payload + 4).toInt()
        if (count <= 0 || count > 10_000_000) return null
        val step = if (wide) 8 else 4
        if (payload + 8 + count.toLong() * step > end) return null
        return LongArray(count) { index ->
            val offset = payload + 8 + index * step
            if (wide) u64(data, offset) else u32(data, offset)
        }
    }

    private fun expandStsc(
        entries: List<StscEntry>,
        chunkCount: Int,
        sampleCount: Int
    ): IntArray? {
        val result = IntArray(chunkCount)
        var group = 0
        var total = 0
        for (chunk in 0 until chunkCount) {
            val number = chunk + 1
            while (group + 1 < entries.size && entries[group + 1].firstChunk <= number) {
                group++
            }
            val perChunk = entries[group].samplesPerChunk
            if (perChunk <= 0) return null
            result[chunk] = perChunk
            total += perChunk
            if (total > sampleCount) return null
        }
        return if (total == sampleCount) result else null
    }

    // ------------------------------------------------------------------
    // Сборка FLAC
    // ------------------------------------------------------------------

    private fun writeFlac(source: File, target: File, tables: Tables): Boolean {
        val fileLength = source.length()
        val out = ByteArrayOutputStream(8 + tables.streamInfo.size)
        out.write("fLaC".toByteArray(Charsets.ISO_8859_1))
        out.write(tables.streamInfo)

        return try {
            FileOutputStream(target).use { output ->
                output.write(out.toByteArray())
                RandomAccessFile(source, "r").use { raf ->
                    val buffer = ByteArray(64 * 1024)
                    var sample = 0
                    for (chunk in tables.chunks.indices) {
                        var offset = tables.chunks[chunk]
                        repeat(tables.samplesPerChunk[chunk]) {
                            if (sample >= tables.sampleSizes.size) return false
                            val size = tables.sampleSizes[sample].toLong()
                            if (size <= 0 || size > MAX_SAMPLE || offset + size > fileLength) {
                                return false
                            }
                            raf.seek(offset)
                            var left = size
                            var first = true
                            while (left > 0) {
                                val count = minOf(buffer.size.toLong(), left).toInt()
                                raf.readFully(buffer, 0, count)
                                if (first) {
                                    if (count < 2 ||
                                        (buffer[0].toInt() and 0xFF) != 0xFF ||
                                        (buffer[1].toInt() and 0xFC) != 0xF8
                                    ) {
                                        return false
                                    }
                                    first = false
                                }
                                output.write(buffer, 0, count)
                                left -= count
                            }
                            offset += size
                            sample++
                        }
                    }
                    if (sample != tables.sampleSizes.size) return false
                }
                output.flush()
            }
            if (target.length() <= 0) {
                target.delete()
                false
            } else {
                true
            }
        } catch (_: Throwable) {
            target.delete()
            false
        }
    }

    // ------------------------------------------------------------------
    // Атомы
    // ------------------------------------------------------------------

    private fun boxes(data: ByteArray, start: Int, end: Int): List<Box> {
        val result = ArrayList<Box>()
        var offset = start
        while (offset + 8 <= end) {
            val box = parseBox(data, offset, end) ?: break
            result.add(box)
            offset = box.start + box.size
        }
        return result
    }

    private fun child(data: ByteArray, parent: Box, type: String): Box? {
        val start = parent.start + parent.header
        val end = parent.start + parent.size
        if (parent.type !in CONTAINERS || start >= end) return null
        return boxes(data, start, end).firstOrNull { it.type == type }
    }

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
        if (size < header || size > Int.MAX_VALUE || offset + size > end) return null
        return Box(type, offset, size.toInt(), header)
    }

    private fun headerLen(data: ByteArray): Int =
        if (data.size >= 8 && u32(data, 0) == 1L) 16 else 8

    private fun u16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or
            (data[offset + 1].toInt() and 0xFF)

    private fun u32(data: ByteArray, offset: Int): Long =
        ((data[offset].toLong() and 0xFF) shl 24) or
            ((data[offset + 1].toLong() and 0xFF) shl 16) or
            ((data[offset + 2].toLong() and 0xFF) shl 8) or
            (data[offset + 3].toLong() and 0xFF)

    private fun u64(data: ByteArray, offset: Int): Long =
        (u32(data, offset) shl 32) or u32(data, offset + 4)
}
