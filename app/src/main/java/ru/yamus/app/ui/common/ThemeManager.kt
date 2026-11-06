package ru.yamus.app.ui.common

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat
import ru.yamus.app.R
import ru.yamus.app.core.auth.TokenStorage

object ThemeManager {

    /** Цвета темы не меняются до пересоздания activity — кэшируем разбор атрибутов. */
    private val colors = HashMap<Int, Int>()
    private var cachedTheme = 0

    fun themeRes(context: Context): Int = when (TokenStorage.theme) {
        TokenStorage.THEME_BLACK -> R.style.Theme_YaMus_Black
        TokenStorage.THEME_LIGHT -> R.style.Theme_YaMus_Light
        else -> R.style.Theme_YaMus_Dark
    }

    fun apply(activity: Activity) {
        val theme = themeRes(activity)
        synchronized(colors) {
            if (theme != cachedTheme) {
                colors.clear()
                cachedTheme = theme
            }
        }
        activity.setTheme(theme)
    }

    fun color(context: Context, attr: Int): Int {
        synchronized(colors) { colors[attr]?.let { return it } }
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        val result = if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
        synchronized(colors) { colors[attr] = result }
        return result
    }
}
