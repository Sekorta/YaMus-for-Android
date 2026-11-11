package ru.yamus.app.ui.common

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import ru.yamus.app.R
import ru.yamus.app.core.util.dp

/**
 * Всё, что зависит от выбранного стиля, но не от палитры.
 *
 * Цвета берутся из темы (Black/Dark/Light), а геометрия — из стиля
 * (современный или Android 4). Поэтому один и тот же стиль выглядит
 * уместно с любой темой.
 */

private val coverOptionsCache = HashMap<Int, RequestOptions>()

/** Скругление обложек. В стиле Android 4 обложки остаются прямыми. */
fun coverOptions(context: Context, radiusDp: Int): RequestOptions {
    val radiusPx = context.dp(if (ThemeManager.isFlat(context)) 0 else radiusDp)
    synchronized(coverOptionsCache) {
        return coverOptionsCache.getOrPut(radiusPx) {
            if (radiusPx <= 0) {
                RequestOptions()
            } else {
                RequestOptions.bitmapTransform(RoundedCorners(radiusPx))
            }
        }
    }
}

/** Аватары: в современном стиле круглые, в Android 4 — квадратные. */
fun avatarOptions(context: Context): RequestOptions =
    if (ThemeManager.isFlat(context)) RequestOptions() else RequestOptions.circleCropTransform()

/**
 * Фон поля поиска. В современном стиле это скруглённая плашка, в стиле
 * Android 4 — прямоугольник с тонкой рамкой, как поля Holo.
 */
fun fieldBackground(context: Context): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        if (ThemeManager.isFlat(context)) {
            cornerRadius = 0f
            setColor(Color.TRANSPARENT)
            setStroke(
                context.dp(1).coerceAtLeast(1),
                ThemeManager.color(context, R.attr.yamusDivider)
            )
        } else {
            cornerRadius = context.dp(23).toFloat()
            setColor(ThemeManager.color(context, R.attr.yamusCard))
        }
    }

/** Заголовки разделов: в Android 4 — капсом и акцентом. */
fun TextView.applySectionHeaderStyle() {
    val flat = ThemeManager.isFlat(context)
    isAllCaps = flat
    setTextColor(
        ThemeManager.color(
            context,
            if (flat) R.attr.yamusAccent else R.attr.yamusTextSub
        )
    )
}

/**
 * Акцентная кнопка с состоянием нажатия. Штатная кнопка AppCompat на
 * Android 4.x выглядит серой независимо от темы, поэтому основные действия
 * (вход) красим в акцент вручную.
 */
fun accentButtonBackground(context: Context): StateListDrawable {
    val accent = ThemeManager.color(context, R.attr.yamusAccent)
    val pressed = ColorUtils.blendARGB(accent, Color.BLACK, 0.18f)
    val radius = if (ThemeManager.isFlat(context)) {
        0f
    } else {
        context.dp(6).toFloat()
    }

    fun shape(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
    }

    return StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), shape(pressed))
        addState(intArrayOf(android.R.attr.state_enabled), shape(accent))
        addState(intArrayOf(), shape(accent))
    }
}
