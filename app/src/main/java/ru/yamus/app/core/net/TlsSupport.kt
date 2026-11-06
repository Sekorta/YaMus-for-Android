package ru.yamus.app.core.net

import android.os.Build
import android.util.Log
import okhttp3.OkHttpClient
import org.conscrypt.Conscrypt
import java.security.KeyStore
import java.security.Provider
import java.security.Security
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Включает современный TLS на старых версиях Android.
 *
 * Android 4.x по умолчанию не умеет TLS 1.2 (а местами — и современные
 * шифры), поэтому запросы к oauth.yandex.ru и api.music.yandex.net падают
 * ещё на рукопожатии. Conscrypt подменяет системный TLS-провайдер и даёт
 * TLS 1.2/1.3, SNI и актуальные шифры на API 9+.
 *
 * На Android 5.0+ ничего не меняем: там современный TLS уже системный.
 *
 * [configure] дополнительно прибивает Conscrypt к конкретному OkHttp-клиенту.
 * Это важно на устройствах, где встроенный провайдер не удалось поставить
 * первым (тогда OkHttp выбрал бы старый системный TLS).
 */
object TlsSupport {
    private const val TAG = "TlsSupport"

    @Volatile
    private var conscryptSocketFactory: SSLSocketFactory? = null

    @Volatile
    private var conscryptTrustManager: X509TrustManager? = null

    /** Короткое описание активного TLS — показывается в ошибках входа. */
    @Volatile
    var status: String = "системный TLS"
        private set

    fun install() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            status = "системный TLS"
            return
        }
        try {
            val provider = Conscrypt.newProvider()
            // На некоторых прошивках insertProviderAt может кинуть исключение,
            // но сам провайдер после этого всё равно пригоден для SSLContext.
            try {
                Security.insertProviderAt(provider, 1)
            } catch (_: Throwable) {
            }

            val trustManager = findTrustManager(provider)
            if (trustManager == null) {
                throw IllegalStateException("нет X509TrustManager")
            }

            // Устройства с севшей батарейкой RTC стартуют в 2012 году, а
            // сертификаты Яндекса выпущены позже — иначе вход невозможен.
            val skewAware = ClockSkewTrustManager(trustManager)

            val context = SSLContext.getInstance("TLS", provider)
            context.init(null, arrayOf<TrustManager>(skewAware), null)
            SSLContext.setDefault(context)

            conscryptSocketFactory = context.socketFactory
            conscryptTrustManager = skewAware
            status = "Conscrypt (TLS 1.2)"
            Log.i(TAG, "TLS: $status")
        } catch (t: Throwable) {
            status = "системный TLS, Conscrypt не загрузился (${t.javaClass.simpleName})"
            Log.e(TAG, "TLS: $status", t)
        }
    }

    /** Прибивает Conscrypt к OkHttp-клиенту, если он доступен. */
    fun configure(builder: OkHttpClient.Builder): OkHttpClient.Builder {
        val socketFactory = conscryptSocketFactory
        val trustManager = conscryptTrustManager
        if (socketFactory != null && trustManager != null) {
            builder.sslSocketFactory(socketFactory, trustManager)
        }
        return builder
    }

    private fun findTrustManager(provider: Provider): X509TrustManager? {
        val attempts = ArrayList<Pair<String, Provider?>>()
        attempts.add("X.509" to provider)
        attempts.add(TrustManagerFactory.getDefaultAlgorithm() to provider)
        attempts.add(TrustManagerFactory.getDefaultAlgorithm() to null)
        for ((algorithm, prov) in attempts) {
            try {
                val factory = if (prov != null) {
                    TrustManagerFactory.getInstance(algorithm, prov)
                } else {
                    TrustManagerFactory.getInstance(algorithm)
                }
                factory.init(null as KeyStore?)
                factory.trustManagers
                    .filterIsInstance<X509TrustManager>()
                    .firstOrNull()
                    ?.let { return it }
            } catch (_: Throwable) {
            }
        }
        return null
    }
}
