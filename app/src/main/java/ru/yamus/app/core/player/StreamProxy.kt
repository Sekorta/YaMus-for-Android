package ru.yamus.app.core.player

import java.io.BufferedInputStream
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Локальный HTTP-сервер, который отдаёт расшифрованный аудиопоток MediaPlayer'у.
 *
 * Яндекс отдаёт файлы в транспорте encraw — AES-128-CTR. Расшифровка на лету
 * позволяет не скачивать трек целиком и поддерживает перемотку: MediaPlayer
 * запрашивает Range, мы открываем upstream с выровненного по 16 байтам смещения
 * и инициализируем счётчик AES с нужного блока.
 */
object StreamProxy {

    data class Source(
        val url: String,
        val keyHex: String?,
        val contentType: String = "audio/mpeg",
        /** Кодек из ответа API: mp3, aac, aac-mp4, flac, flac-mp4. */
        val codec: String = ""
    )

    private val started = AtomicBoolean(false)

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var port: Int = 0

    @Volatile
    private var source: Source? = null

    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            val socket = ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            port = socket.localPort
            android.util.Log.i("YaMusProxy", "started on port $port")
            val thread = Thread({ acceptLoop(socket) }, "YaMusStreamProxy")
            thread.isDaemon = true
            thread.start()
        } catch (e: Exception) {
            android.util.Log.e("YaMusProxy", "start failed", e)
            started.set(false)
        }
    }

    fun setSource(value: Source): String {
        start()
        source = value
        return "http://127.0.0.1:$port/stream"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            try {
                val client = socket.accept()
                val thread = Thread({ handle(client) }, "YaMusStreamClient")
                thread.isDaemon = true
                thread.start()
            } catch (e: Exception) {
                if (socket.isClosed) break
            }
        }
    }

    private fun handle(client: Socket) {
        try {
            // Не используем client.use {}: на Android 4.x java.net.Socket
            // не реализует java.io.Closeable, и Kotlin-овский use падает
            // с ClassCastException ещё до чтения запроса.
            val input = BufferedInputStream(client.getInputStream())
            val reader = BufferedReader(InputStreamReader(input, "ISO-8859-1"))
            val requestLine = reader.readLine() ?: return
            var rangeStart = 0L
            var rangeEnd = -1L
            var line: String? = reader.readLine()
            while (!line.isNullOrEmpty()) {
                if (line.startsWith("Range:", ignoreCase = true)) {
                    val value = line.substringAfter("=", "").trim()
                    val parts = value.split("-")
                    rangeStart = parts.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
                    rangeEnd = parts.getOrNull(1)?.trim()?.toLongOrNull() ?: -1L
                }
                line = reader.readLine()
            }
            val current = source ?: return
            val headOnly = requestLine.startsWith("HEAD", ignoreCase = true)
            android.util.Log.d("YaMusProxy", "client: $requestLine range=$rangeStart-$rangeEnd")
            serve(client.getOutputStream(), current, rangeStart, rangeEnd, headOnly)
        } catch (e: Exception) {
            android.util.Log.w("YaMusProxy", "client failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                client.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun serve(
        output: OutputStream,
        src: Source,
        start: Long,
        requestedEnd: Long,
        headOnly: Boolean
    ) {
        val aligned = start - (start % 16L)
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(src.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "*/*")
                if (aligned > 0) setRequestProperty("Range", "bytes=$aligned-")
            }

            val code = connection.responseCode
            android.util.Log.d("YaMusProxy", "upstream $code for ${src.url.take(60)}")
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                writeError(output)
                return
            }

            val contentRange = connection.getHeaderField("Content-Range")
            var total = -1L
            if (contentRange != null) {
                total = contentRange.substringAfterLast("/", "").toLongOrNull() ?: -1L
            }
            if (total <= 0) {
                val lengthHeader = connection.getHeaderField("Content-Length")
                total = lengthHeader?.toLongOrNull() ?: connection.contentLength.toLong()
            }

            val upstreamStart = if (code == HttpURLConnection.HTTP_PARTIAL) aligned else 0L
            val skip = (start - upstreamStart).coerceAtLeast(0L)
            val end = when {
                requestedEnd >= 0 && total > 0 -> minOf(requestedEnd, total - 1)
                total > 0 -> total - 1
                else -> -1L
            }
            val length = if (total > 0 && end >= 0) end - start + 1 else -1L

            val headers = StringBuilder()
            headers.append("HTTP/1.1 206 Partial Content\r\n")
            headers.append("Content-Type: ").append(src.contentType).append("\r\n")
            headers.append("Accept-Ranges: bytes\r\n")
            if (total > 0 && end >= 0) {
                headers.append("Content-Range: bytes ").append(start).append('-')
                    .append(end).append('/').append(total).append("\r\n")
            }
            if (length > 0) {
                headers.append("Content-Length: ").append(length).append("\r\n")
            }
            headers.append("Connection: close\r\n\r\n")
            output.write(headers.toString().toByteArray(Charsets.ISO_8859_1))
            output.flush()
            if (headOnly) return

            val cipher = src.keyHex?.takeIf { it.isNotBlank() }?.let { createCipher(it, upstreamStart) }
            val input = connection.inputStream
            val buffer = ByteArray(64 * 1024)
            // Как и в TrackDownloader: расшифровываем в переиспользуемый буфер,
            // чтобы не плодить мусор и не провоцировать частые GC при проигрывании.
            val plain = if (cipher != null) ByteArray(64 * 1024 + 32) else null
            var useOffsetUpdate = cipher != null
            var skipped = 0L
            var written = 0L

            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                var data = buffer
                var offset = 0
                var count = read
                if (cipher != null && plain != null) {
                    if (useOffsetUpdate) {
                        val decrypted = try {
                            cipher.update(buffer, 0, read, plain, 0)
                        } catch (_: Exception) {
                            useOffsetUpdate = false
                            -1
                        }
                        if (useOffsetUpdate) {
                            count = decrypted
                            data = plain
                        }
                    }
                    if (!useOffsetUpdate) {
                        val decrypted = cipher.update(buffer, 0, read) ?: continue
                        data = decrypted
                        count = decrypted.size
                    }
                    if (count <= 0) continue
                }
                if (skipped < skip) {
                    val toSkip = minOf(skip - skipped, count.toLong()).toInt()
                    offset += toSkip
                    count -= toSkip
                    skipped += toSkip
                }
                if (count > 0) {
                    if (length > 0 && written + count > length) {
                        count = (length - written).toInt()
                    }
                    if (count > 0) {
                        output.write(data, offset, count)
                        output.flush()
                        written += count
                    }
                }
                if (length > 0 && written >= length) break
            }
        } catch (e: Exception) {
            android.util.Log.w("YaMusProxy", "serve failed: ${e.javaClass.simpleName}: ${e.message}")
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) {
            }
        }
    }

    internal fun createCipher(keyHex: String, base: Long): Cipher? {
        return try {
            val key = hexToBytes(keyHex)
            if (key.size != 16) return null
            val block = base / 16L
            val iv = ByteArray(16)
            for (i in 0..7) {
                iv[15 - i] = ((block ushr (8 * i)) and 0xFF).toByte()
            }
            val cipher = Cipher.getInstance("AES/CTR/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, "AES"),
                IvParameterSpec(iv)
            )
            cipher
        } catch (_: Exception) {
            null
        }
    }

    private fun hexToBytes(value: String): ByteArray {
        val hex = value.trim()
        if (hex.length % 2 != 0) return ByteArray(0)
        val out = ByteArray(hex.length / 2)
        var i = 0
        while (i < hex.length) {
            out[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return out
    }

    private fun writeError(output: OutputStream) {
        try {
            val body = "stream error".toByteArray(Charsets.UTF_8)
            val headers = "HTTP/1.1 502 Bad Gateway\r\nContent-Type: text/plain\r\n" +
                "Content-Length: ${body.size}\r\nConnection: close\r\n\r\n"
            output.write(headers.toByteArray(Charsets.ISO_8859_1))
            output.write(body)
            output.flush()
        } catch (_: Exception) {
        }
    }
}
