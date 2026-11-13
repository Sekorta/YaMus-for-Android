package ru.yamus.app.core.auth

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object TokenStorage {
    private const val PREFS = "yamus_prefs"
    private const val KEY_TOKEN = "oauth_token"
    private const val KEY_THEME = "theme"
    private const val KEY_STYLE = "style"
    private const val KEY_QUALITY = "quality"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_UID = "uid"
    private const val KEY_AUTO_CLEAR_CACHE = "auto_clear_cache"
    private const val KEY_AUTO_DOWNLOAD = "auto_download"
    private const val KEY_AUTO_DOWNLOAD_LIKED = "auto_download_liked"
    private const val KEY_WIFI_ONLY = "wifi_only_downloads"
    private const val KEY_DOWNLOADS_SORT = "downloads_sort"

    const val THEME_BLACK = "black"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    /** Современный стиль: скругления, карточки, воздух. */
    const val STYLE_MODERN = "modern"

    /** Стиль Android 4 (Holo): прямые углы, плоские панели, плотные списки. */
    const val STYLE_HOLO = "holo"

    /** Псевдотерминал: моноширинный шрифт, рамки, приглашение и курсор. */
    const val STYLE_TERMINAL = "terminal"

    const val QUALITY_MAX = "max"
    const val QUALITY_MEDIUM = "medium"
    const val QUALITY_LOW = "low"

    const val SORT_ARTIST = "artist"
    const val SORT_TITLE = "title"
    const val SORT_NEWEST = "newest"
    const val SORT_SIZE = "size"
    const val SORT_DURATION = "duration"

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value.isNullOrEmpty()) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
            }.apply()
        }

    var theme: String
        get() {
            val value = prefs.getString(KEY_THEME, THEME_DARK) ?: THEME_DARK
            return when (value) {
                THEME_BLACK, THEME_LIGHT, THEME_DARK -> value
                else -> THEME_DARK
            }
        }
        set(value) {
            prefs.edit().putString(KEY_THEME, value).apply()
        }

    var style: String
        get() {
            val value = prefs.getString(KEY_STYLE, STYLE_MODERN) ?: STYLE_MODERN
            return when (value) {
                STYLE_MODERN, STYLE_HOLO, STYLE_TERMINAL -> value
                else -> STYLE_MODERN
            }
        }
        set(value) {
            prefs.edit().putString(KEY_STYLE, value).apply()
        }

    var quality: String
        get() {
            val value = prefs.getString(KEY_QUALITY, QUALITY_MAX) ?: QUALITY_MAX
            return when (value) {
                QUALITY_MAX, QUALITY_MEDIUM, QUALITY_LOW -> value
                else -> QUALITY_MAX
            }
        }
        set(value) {
            prefs.edit().putString(KEY_QUALITY, value).apply()
        }

    var uid: Long
        get() = prefs.getLong(KEY_UID, 0L)
        set(value) {
            prefs.edit().putLong(KEY_UID, value).apply()
        }

    /**
     * Автоматически чистить приватный кэш треков и обложек при выходе.
     * Публичная «YaMus Library» не затрагивается никогда.
     */
    var autoClearCache: Boolean
        get() = prefs.getBoolean(KEY_AUTO_CLEAR_CACHE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_CLEAR_CACHE, value).apply()
        }

    /** Скачивать в «YaMus Library» каждый прослушанный трек. */
    var autoDownload: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD, value).apply()
        }

    /** Автоскачивание только треков, запущенных из «Мне нравится». */
    var autoDownloadLikedOnly: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DOWNLOAD_LIKED, false)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_DOWNLOAD_LIKED, value).apply()
        }

    /** Автоскачивание только по Wi-Fi, чтобы не тратить мобильный трафик. */
    var wifiOnlyDownloads: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) {
            prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()
        }

    /** Порядок треков на экране «Скачанные треки». */
    var downloadsSort: String
        get() {
            val value = prefs.getString(KEY_DOWNLOADS_SORT, SORT_ARTIST) ?: SORT_ARTIST
            return when (value) {
                SORT_ARTIST, SORT_TITLE, SORT_NEWEST, SORT_SIZE, SORT_DURATION -> value
                else -> SORT_ARTIST
            }
        }
        set(value) {
            prefs.edit().putString(KEY_DOWNLOADS_SORT, value).apply()
        }

    val deviceId: String
        get() {
            val existing = prefs.getString(KEY_DEVICE_ID, null)
            if (!existing.isNullOrEmpty()) return existing
            val generated = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DEVICE_ID, generated).apply()
            return generated
        }

    fun clear() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_UID)
            .apply()
    }
}
