package ru.yamus.app.core.player

import android.os.Build
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Request
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.models.FileInfoResponse
import ru.yamus.app.core.models.Track
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Получение прямой ссылки на аудиопоток через /get-file-info.
 *
 * Подпись: HMAC-SHA256(ts + trackId + quality + codecs + transport), ключ
 * десктопного клиента, base64 без padding. Порядок попыток качества повторяет
 * Windows-клиент: максимальное → lossless/flac, затем hq/aac, затем nq/mp3.
 */
object TrackUrlResolver {

    private const val HMAC_KEY = "kzqU4XhfCaY6B6JTHODeq5"
    private const val API_BASE = "https://api.music.yandex.net"

    private data class Attempt(val quality: String, val codecs: List<String>)

    private val maxAttempts = listOf(
        Attempt("lossless", listOf("flac")),
        Attempt("hq", listOf("aac")),
        Attempt("nq", listOf("mp3", "aac", "he-aac"))
    )

    private val mediumAttempts = listOf(
        Attempt("nq", listOf("mp3", "aac", "he-aac")),
        Attempt("lq", listOf("he-aac", "aac"))
    )

    private val lowAttempts = listOf(
        Attempt("lq", listOf("he-aac", "aac")),
        Attempt("nq", listOf("mp3", "aac", "he-aac"))
    )

    suspend fun resolve(track: Track): StreamProxy.Source? = withContext(Dispatchers.IO) {
        val trackId = track.id
        if (trackId.isBlank()) return@withContext null

        val attempts = when (TokenStorage.quality) {
            TokenStorage.QUALITY_LOW -> lowAttempts
            TokenStorage.QUALITY_MEDIUM -> mediumAttempts
            else -> maxAttempts
        }

        // На старых Android FLAC/AAC в контейнерах может не проигрываться,
        // поэтому там начинаем с обычного MP3.
        val ordered = if (Build.VERSION.SDK_INT < 21) {
            attempts.sortedBy { if (it.codecs.contains("mp3")) 0 else 1 }
        } else {
            attempts
        }

        for (attempt in ordered) {
            val source = tryResolve(trackId, attempt)
            if (source != null) return@withContext source
        }
        null
    }

    private fun tryResolve(trackId: String, attempt: Attempt): StreamProxy.Source? {
        val timestamp = System.currentTimeMillis() / 1000L
        val payload = buildString {
            append(timestamp)
            append(trackId)
            append(attempt.quality)
            attempt.codecs.forEach { append(it) }
            append("encraw")
        }
        val sign = sign(payload) ?: return null

        val url: HttpUrl = HttpUrl.parse("$API_BASE/get-file-info")!!.newBuilder()
            .addQueryParameter("ts", timestamp.toString())
            .addQueryParameter("trackId", trackId)
            .addQueryParameter("quality", attempt.quality)
            .addQueryParameter("codecs", attempt.codecs.joinToString(","))
            .addQueryParameter("transports", "encraw")
            .addQueryParameter("sign", sign)
            .build()

        val request = Request.Builder()
            .url(url)
            .header("X-Yandex-Music-Client", "YandexMusicDesktopAppWindows/5.66.1")
            .header("Accept", "application/json")
            .header("Accept-Language", "ru")
            .apply {
                TokenStorage.token?.takeIf { it.isNotEmpty() }?.let {
                    header("Authorization", "OAuth $it")
                }
            }
            .build()

        return try {
            ApiClient.http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return null
                val body = response.body()?.string() ?: return null
                val info = ApiClient.gson.fromJson(body, FileInfoResponse::class.java)
                    ?.result?.downloadInfo ?: return null
                val link = info.url ?: info.urls?.firstOrNull() ?: return null
                val key = if (info.transport == "encraw") info.key else null
                if (key.isNullOrBlank()) return null
                StreamProxy.Source(
                    url = link,
                    keyHex = key,
                    contentType = contentType(info.codec)
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun contentType(codec: String?): String {
        val value = codec.orEmpty().lowercase()
        return when {
            value.contains("flac") -> "audio/flac"
            value.contains("mp4") -> "audio/mp4"
            value == "aac" || value == "he-aac" -> "audio/aac"
            else -> "audio/mpeg"
        }
    }

    private fun sign(payload: String): String? {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(HMAC_KEY.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val digest = mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(digest, Base64.NO_WRAP).trimEnd('=')
        } catch (_: Exception) {
            null
        }
    }
}
