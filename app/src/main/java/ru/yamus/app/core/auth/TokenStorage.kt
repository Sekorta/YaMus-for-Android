package ru.yamus.app.core.auth

import android.content.Context
import android.content.SharedPreferences
import java.util.UUID

object TokenStorage {
    private const val PREFS = "yamus_prefs"
    private const val KEY_TOKEN = "oauth_token"
    private const val KEY_THEME = "theme"
    private const val KEY_QUALITY = "quality"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_UID = "uid"

    const val THEME_BLACK = "black"
    const val THEME_DARK = "dark"
    const val THEME_LIGHT = "light"

    const val QUALITY_MAX = "max"
    const val QUALITY_MEDIUM = "medium"
    const val QUALITY_LOW = "low"

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
