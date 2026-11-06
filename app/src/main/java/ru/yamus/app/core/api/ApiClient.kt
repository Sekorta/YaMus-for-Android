package ru.yamus.app.core.api

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import ru.yamus.app.core.net.TlsSupport
import java.util.concurrent.TimeUnit

object ApiClient {
    private const val BASE_URL = "https://api.music.yandex.net/"
    private const val CLIENT_HEADER = "YandexMusicDesktopAppWindows/5.97.0"

    @Volatile
    var token: String? = null

    val gson: Gson = GsonBuilder().create()

    private val authInterceptor = Interceptor { chain ->
        val builder = chain.request().newBuilder()
            .header("X-Yandex-Music-Client", CLIENT_HEADER)
            .header("Accept", "application/json")
            .header("Accept-Language", "ru")
        val current = token
        if (!current.isNullOrEmpty()) {
            builder.header("Authorization", "OAuth $current")
        }
        chain.proceed(builder.build())
    }

    private val logging = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BASIC
    }

    val http: OkHttpClient = TlsSupport.configure(
        OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(logging)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
    ).build()

    val api: YandexApi by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(http)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .build()
            .create(YandexApi::class.java)
    }
}
