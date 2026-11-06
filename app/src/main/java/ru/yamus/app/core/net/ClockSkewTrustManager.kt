package ru.yamus.app.core.net

import android.util.Log
import java.security.KeyStore
import java.security.cert.CertPathValidator
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.CertificateNotYetValidException
import java.security.cert.PKIXParameters
import java.security.cert.X509Certificate
import java.util.Date
import javax.net.ssl.X509TrustManager

/**
 * Ошибка «Certificate not valid until ...» на устройствах с севшей батарейкой
 * RTC: Android 4 после выключения стартует в 2012 году, а сертификаты Яндекса
 * выпущены позже. Цепочка и подписи при этом корректные — неверны только часы.
 *
 * Если проверка не прошла именно из-за того, что сертификат «ещё не наступил»,
 * повторяем стандартную PKIX-проверку по системным корневым сертификатам,
 * подставив дату из самой цепочки (сразу после начала действия сертификатов).
 * Просроченные сертификаты по-прежнему не принимаются.
 */
class ClockSkewTrustManager(
    private val delegate: X509TrustManager
) : X509TrustManager {

    private companion object {
        const val TAG = "ClockSkew"
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        delegate.checkClientTrusted(chain, authType)
    }

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        try {
            delegate.checkServerTrusted(chain, authType)
        } catch (error: CertificateException) {
            if (!shouldRetry(chain, error)) throw error
            try {
                validateWithChainDate(chain)
                Log.w(
                    TAG,
                    "Часы устройства раньше сертификата — проверка выполнена по дате из цепочки"
                )
            } catch (_: Throwable) {
                // Если проверка по дате из цепочки не удалась, показываем
                // исходную ошибку — она понятнее для пользователя.
                throw error
            }
        }
    }

    private fun shouldRetry(chain: Array<X509Certificate>, error: Throwable): Boolean {
        if (chain.isEmpty()) return false
        // Только случай «часы устройства раньше сертификатов». Если часы,
        // наоборот, убежали вперёд и сертификат просрочен — не помогаем.
        val latestStart = chain.maxOfOrNull { it.notBefore.time } ?: return false
        if (System.currentTimeMillis() >= latestStart) return false

        var cause: Throwable? = error
        var depth = 0
        while (cause != null && depth < 8) {
            if (cause is CertificateNotYetValidException) return true
            cause = cause.cause
            depth++
        }
        return false
    }

    private fun validateWithChainDate(chain: Array<X509Certificate>) {
        val validationDate = Date(chain.maxOf { it.notBefore.time } + 60_000L)
        val trustStore = KeyStore.getInstance("AndroidCAStore").apply {
            load(null, null)
        }
        val parameters = PKIXParameters(trustStore).apply {
            isRevocationEnabled = false
            date = validationDate
        }
        val certPath = CertificateFactory.getInstance("X.509")
            .generateCertPath(chain.toList())
        CertPathValidator.getInstance("PKIX").validate(certPath, parameters)
    }
}
