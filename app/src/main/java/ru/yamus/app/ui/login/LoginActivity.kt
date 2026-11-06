package ru.yamus.app.ui.login

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import ru.yamus.app.R
import ru.yamus.app.core.api.ApiClient
import ru.yamus.app.core.auth.TokenStorage
import ru.yamus.app.core.data.AccountStore
import ru.yamus.app.core.net.TlsSupport
import ru.yamus.app.ui.common.BaseActivity
import ru.yamus.app.ui.main.MainActivity
import java.util.concurrent.TimeUnit

class LoginActivity : BaseActivity() {

    private companion object {
        const val TAG = "YaMusLogin"
        const val CLIENT_ID = "23cabbbdc6cd418abb4b39c32c41195d"
        const val CLIENT_SECRET = "53bc75238f0c4d08a118e51fe9203300"
    }

    private data class DeviceCode(
        val deviceCode: String,
        val userCode: String,
        val verificationUrl: String,
        val interval: Int
    )

    private sealed class TokenResult {
        class Success(val token: String) : TokenResult()
        object Pending : TokenResult()
        class Failure(val message: String) : TokenResult()
    }

    private val http: OkHttpClient by lazy {
        TlsSupport.configure(
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
        ).build()
    }

    private lateinit var tokenInput: EditText
    private lateinit var saveToken: Button
    private lateinit var tokenInfo: TextView
    private lateinit var deviceLogin: Button
    private lateinit var deviceInfo: TextView
    private lateinit var deviceCodeLabel: TextView
    private lateinit var deviceCode: TextView
    private lateinit var deviceLink: TextView
    private lateinit var progress: ProgressBar
    private lateinit var scroll: ScrollView

    private var deviceJob: Job? = null
    private var lastDeviceCodeError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val existing = TokenStorage.token
        if (!existing.isNullOrEmpty()) {
            ApiClient.token = (existing)
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }

        setContentView(R.layout.activity_login)
        tokenInput = findViewById(R.id.tokenInput)
        saveToken = findViewById(R.id.saveToken)
        tokenInfo = findViewById(R.id.tokenInfo)
        deviceLogin = findViewById(R.id.deviceLogin)
        deviceInfo = findViewById(R.id.deviceInfo)
        deviceCodeLabel = findViewById(R.id.deviceCodeLabel)
        deviceCode = findViewById(R.id.deviceCode)
        deviceLink = findViewById(R.id.deviceLink)
        progress = findViewById(R.id.loginProgress)
        scroll = findViewById(R.id.loginScroll)

        Log.i(TAG, "TLS: ${TlsSupport.status}")

        saveToken.setOnClickListener {
            val token = tokenInput.text.toString().trim()
            if (token.isEmpty()) {
                Toast.makeText(this, "Введите токен", Toast.LENGTH_SHORT).show()
            } else {
                tokenInfo.visibility = View.GONE
                loginWithToken(token)
            }
        }

        deviceLogin.setOnClickListener { startDeviceFlow() }
    }

    private fun loginWithToken(token: String) {
        saveToken.isEnabled = false
        deviceLogin.isEnabled = false
        progress.visibility = View.VISIBLE

        lifecycleScope.launch {
            val previous = ApiClient.token
            ApiClient.token = (token)
            val error = withContext(Dispatchers.IO) {
                try {
                    val account = ApiClient.api.getAccountAbout().result
                    if (account == null) "сервер не вернул аккаунт" else null
                } catch (e: Exception) {
                    Log.e(TAG, "Проверка токена не удалась", e)
                    describe(e)
                }
            }
            if (error != null) {
                ApiClient.token = (previous)
                progress.visibility = View.GONE
                saveToken.isEnabled = true
                deviceLogin.isEnabled = true
                tokenInfo.text = "Не удалось войти: $error\nTLS: ${TlsSupport.status}"
                tokenInfo.visibility = View.VISIBLE
                scrollTo(tokenInfo)
                return@launch
            }

            TokenStorage.token = token
            AccountStore.load(true)
            openMain()
        }
    }

    /** Подробное описание ошибки для показа на экране входа. */
    private fun describe(error: Throwable): String {
        val parts = ArrayList<String>()
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < 4) {
            val message = current.message?.takeIf { it.isNotBlank() }
            parts.add(
                if (message != null) "${current.javaClass.simpleName}: $message"
                else current.javaClass.simpleName
            )
            current = current.cause
            depth++
        }
        return parts.joinToString(" → ")
    }

    private fun scrollTo(view: View) {
        scroll.post {
            scroll.smoothScrollTo(0, (view.top - 12).coerceAtLeast(0))
        }
    }

    private fun startDeviceFlow() {
        if (deviceJob?.isActive == true) return
        deviceLogin.isEnabled = false
        progress.visibility = View.VISIBLE
        deviceInfo.visibility = View.GONE
        deviceCodeLabel.visibility = View.GONE
        deviceCode.visibility = View.GONE
        deviceLink.visibility = View.GONE

        deviceJob = lifecycleScope.launch {
            lastDeviceCodeError = null
            val code = withContext(Dispatchers.IO) { requestDeviceCode() }
            if (code == null) {
                progress.visibility = View.GONE
                deviceLogin.isEnabled = true
                val details = lastDeviceCodeError ?: "неизвестная ошибка"
                deviceInfo.text = "Не удалось получить код: $details\nTLS: ${TlsSupport.status}"
                deviceInfo.visibility = View.VISIBLE
                scrollTo(deviceInfo)
                return@launch
            }

            progress.visibility = View.GONE
            deviceCodeLabel.visibility = View.VISIBLE
            deviceCode.text = code.userCode
            deviceCode.visibility = View.VISIBLE
            deviceLink.text = code.verificationUrl
            deviceLink.visibility = View.VISIBLE
            deviceLink.setOnClickListener { openUrl(code.verificationUrl) }
            deviceInfo.text = getString(R.string.login_device_wait)
            deviceInfo.visibility = View.VISIBLE
            scrollTo(deviceCodeLabel)

            pollDeviceToken(code)
        }
    }

    private suspend fun pollDeviceToken(code: DeviceCode) {
        while (currentCoroutineContext().isActive) {
            delay(code.interval * 1000L)
            val result = withContext(Dispatchers.IO) { requestToken(code.deviceCode) }
            when (result) {
                is TokenResult.Success -> {
                    TokenStorage.token = result.token
                    ApiClient.token = (result.token)
                    AccountStore.load(true)
                    openMain()
                    return
                }
                is TokenResult.Pending -> {
                    // пользователь ещё не подтвердил — продолжаем опрос
                }
                is TokenResult.Failure -> {
                    progress.visibility = View.GONE
                    deviceLogin.isEnabled = true
                    deviceCodeLabel.visibility = View.GONE
                    deviceCode.visibility = View.GONE
                    deviceLink.visibility = View.GONE
                    val tlsHint = if (result.message.startsWith("сеть:")) {
                        "\nTLS: ${TlsSupport.status}"
                    } else {
                        ""
                    }
                    deviceInfo.text = "Не удалось войти: ${result.message}$tlsHint"
                    deviceInfo.visibility = View.VISIBLE
                    scrollTo(deviceInfo)
                    return
                }
            }
        }
    }

    private fun requestDeviceCode(): DeviceCode? {
        val body = FormBody.Builder()
            .add("client_id", CLIENT_ID)
            .add("device_id", TokenStorage.deviceId)
            .add("device_name", "YaMus Android")
            .build()
        val request = Request.Builder()
            .url("https://oauth.yandex.ru/device/code")
            .post(body)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    lastDeviceCodeError = "HTTP ${response.code()}"
                    Log.e(TAG, "device/code: HTTP ${response.code()}")
                    return null
                }
                val text = response.body()?.string()
                if (text.isNullOrEmpty()) {
                    lastDeviceCodeError = "пустой ответ сервера"
                    return null
                }
                val json = JSONObject(text)
                val deviceCode = json.optString("device_code")
                val userCode = json.optString("user_code")
                if (deviceCode.isEmpty() || userCode.isEmpty()) {
                    lastDeviceCodeError = "в ответе нет кода устройства"
                    return null
                }
                DeviceCode(
                    deviceCode = deviceCode,
                    userCode = userCode,
                    verificationUrl = json.optString(
                        "verification_url",
                        "https://ya.ru/device"
                    ),
                    interval = json.optInt("interval", 5).coerceIn(2, 30)
                ).also {
                    Log.i(TAG, "device/code: OK")
                }
            }
        } catch (e: Exception) {
            lastDeviceCodeError = describe(e)
            Log.e(TAG, "device/code failed", e)
            null
        }
    }

    private fun requestToken(deviceCode: String): TokenResult {
        val body = FormBody.Builder()
            .add("grant_type", "device_code")
            .add("code", deviceCode)
            .add("client_id", CLIENT_ID)
            .add("client_secret", CLIENT_SECRET)
            .build()
        val request = Request.Builder()
            .url("https://oauth.yandex.ru/token")
            .post(body)
            .build()
        return try {
            http.newCall(request).execute().use { response ->
                // В OkHttp тело ответа доступно через body() и для ошибок:
                // Яндекс кладёт JSON с error в тело ответа 400.
                val text = response.body()?.string()
                if (text.isNullOrEmpty()) return TokenResult.Failure("пустой ответ")
                val json = JSONObject(text)
                val token = json.optString("access_token")
                if (token.isNotEmpty()) return TokenResult.Success(token)
                val error = json.optString("error")
                if (error.contains("authorization_pending")) {
                    TokenResult.Pending
                } else {
                    TokenResult.Failure(
                        when {
                            error.contains("expired_token") -> "код устарел"
                            error.contains("access_denied") -> "вход не подтверждён"
                            error.isEmpty() -> "неизвестная ошибка"
                            else -> error
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "token request failed", e)
            TokenResult.Failure("сеть: ${describe(e)}")
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(this, url, Toast.LENGTH_LONG).show()
        }
    }

    private fun openMain() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
