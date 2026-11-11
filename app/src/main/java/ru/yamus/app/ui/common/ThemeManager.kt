package ru.yamus.app.ui.common

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import androidx.core.content.ContextCompat
import ru.yamus.app.R
import ru.yamus.app.core.auth.TokenStorage

object ThemeManager {

    /**
     * Цвета и размеры темы не меняются до пересоздания activity — кэшируем
     * разбор атрибутов. Тема выбирается по паре «палитра + стиль», поэтому
     * смена стиля тоже сбрасывает кэш.
     */
    private val colors = HashMap<Int, Int>()
    private val dimensions = HashMap<Int, Int>()
    private var cachedTheme = 0

    private fun holo(): Boolean = TokenStorage.style == TokenStorage.STYLE_HOLO

    fun themeRes(context: Context): Int {
        val style = holo()
        return when (TokenStorage.theme) {
            TokenStorage.THEME_BLACK ->
                if (style) R.style.Theme_YaMus_Black_Holo else R.style.Theme_YaMus_Black
            TokenStorage.THEME_LIGHT ->
                if (style) R.style.Theme_YaMus_Light_Holo else R.style.Theme_YaMus_Light
            else ->
                if (style) R.style.Theme_YaMus_Dark_Holo else R.style.Theme_YaMus_Dark
        }
    }

    fun apply(activity: Activity) {
        val theme = themeRes(activity)
        synchronized(colors) {
            if (theme != cachedTheme) {
                colors.clear()
                dimensions.clear()
                cachedTheme = theme
            }
        }
        activity.setTheme(theme)
    }

    /** Стиль Android 4: прямые углы, плоские панели, плотные списки. */
    fun isFlat(context: Context): Boolean = holo()

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

    fun dimension(context: Context, attr: Int): Int {
        synchronized(dimensions) { dimensions[attr]?.let { return it } }
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        val result = if (value.resourceId != 0) {
            context.resources.getDimensionPixelSize(value.resourceId)
        } else {
            TypedValue.complexToDimensionPixelSize(value.data, context.resources.displayMetrics)
        }
        synchronized(dimensions) { dimensions[attr] = result }
        return result
    }

    /** Целое значение атрибута (например, радиус обложки в dp). */
    fun integer(context: Context, attr: Int): Int {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return if (value.resourceId != 0) {
            context.resources.getInteger(value.resourceId)
        } else {
            value.data
        }
    }

    fun bool(context: Context, attr: Int): Boolean {
        val value = TypedValue()
        context.theme.resolveAttribute(attr, value, true)
        return value.data != 0
    }
}
