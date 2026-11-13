package ru.yamus.app.core.player

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Общая загрузка аудиопотока с расшифровкой encraw.
 *
 * Используется и приватным кэшем ([TrackDownloader]), и публичной
 * библиотекой: Яндекс отдаёт файлы в AES-128-CTR, поэтому недостаточно
 * просто скопировать ответ. Логика вынесена в один объект, чтобы оба пути
 * одинаково обрабатывали обрывы, пустые ответы и старые прошивки.
 */
object AudioFetcher {

    /**
     * Скачивает [source] в [target] и возвращает число записанных байт
     * либо -1 при ошибке. Файл создаётся заново.
     */
    fun fetch(
        source: StreamProxy.Source,
        target: File,
        onProgress: ((Long) -> Unit)? = null
    ): Long {
        var written = 0L
        try {
            android.os.Process.setThreadPriority(
                android.os.Process.THREAD_PRIORITY_BACKGROUND
            )
        } catch (_: Exception) {
        }

        val connection = try {
            (URL(source.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20000
                readTimeout = 30000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0")
                setRequestProperty("Accept", "*/*")
            }
        } catch (_: Exception) {
            return -1
        }

        val cipher = source.keyHex
            ?.takeIf { it.isNotBlank() }
            ?.let { StreamProxy.createCipher(it, 0L) }

        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                connection.disconnect()
                return -1
            }

            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    // Расшифровка в переиспользуемый буфер: на Android 4.x
                    // каждый cipher.update() с новым массивом создаёт мусор,
                    // из-за которого GC подтормаживает интерфейс.
                    val plain = if (cipher != null) ByteArray(64 * 1024 + 32) else null
                    var useOffsetUpdate = cipher != null
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        if (cipher == null || plain == null) {
                            output.write(buffer, 0, read)
                            written += read
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
                                if (count > 0) {
                                    output.write(plain, 0, count)
                                    written += count
                                }
                                continue
                            }
                        }
                        val data = cipher.update(buffer, 0, read) ?: continue
                        if (data.isNotEmpty()) {
                            output.write(data)
                            written += data.size
                        }
                    }
                    // В CTR-режиме update() отдаёт только полные блоки:
                    // хвост последнего неполного блока появляется лишь
                    // в doFinal(). Без него файл обрезался бы до 15 байт.
                    if (cipher != null) {
                        try {
                            val tail = cipher.doFinal()
                            if (tail != null && tail.isNotEmpty()) {
                                output.write(tail)
                                written += tail.size
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            connection.disconnect()
            if (written <= 0) {
                target.delete()
                return -1
            }
            onProgress?.invoke(written)
            return written
        } catch (_: Exception) {
            connection.disconnect()
            target.delete()
            return -1
        }
    }
}
