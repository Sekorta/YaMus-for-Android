package ru.yamus.app.ui.common

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.bumptech.glide.load.resource.bitmap.RoundedCorners
import com.bumptech.glide.request.RequestOptions
import ru.yamus.app.R
import ru.yamus.app.core.util.dp

/**
 * Всё, что зависит от выбранного стиля, но не от палитры.
 *
 * Цвета берутся из темы (Black/Dark/Light), а геометрия и подача — из стиля
 * (современный, Android 4 или терминал). Поэтому один и тот же стиль
 * выглядит уместно с любой темой.
 */

private val coverOptionsCache = HashMap<Int, RequestOptions>()

/** Скругление обложек. В плоских стилях обложки остаются прямыми. */
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

/** Аватары: в современном стиле круглые, в плоских — квадратные. */
fun avatarOptions(context: Context): RequestOptions =
    if (ThemeManager.isFlat(context)) RequestOptions() else RequestOptions.circleCropTransform()

/**
 * Фон поля поиска. Современный стиль — скруглённая плашка, Android 4 —
 * прямоугольник с тонкой рамкой, терминал — прозрачное поле с акцентной
 * рамкой, как строка ввода в консоли.
 */
fun fieldBackground(context: Context): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        if (ThemeManager.isTerminal(context)) {
            cornerRadius = 0f
            setColor(Color.TRANSPARENT)
            setStroke(
                context.dp(1).coerceAtLeast(1),
                ThemeManager.color(context, R.attr.yamusAccent)
            )
        } else if (ThemeManager.isFlat(context)) {
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

/** Заголовки разделов: в плоских стилях — капсом и акцентом. */
fun TextView.applySectionHeaderStyle() {
    val flat = ThemeManager.isFlat(context)
    isAllCaps = flat
    setTextColor(
        ThemeManager.color(
            context,
            if (flat) R.attr.yamusAccent else R.attr.yamusTextSub
        )
    )
    if (ThemeManager.isTerminal(context)) {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
}

/**
 * Текст заголовка раздела с префиксом-приглашением в терминальном стиле.
 * В остальных стилях текст остаётся как есть.
 */
fun TextView.setSectionHeaderText(text: CharSequence) {
    this.text = if (ThemeManager.isTerminal(context)) "> $text" else text
}

fun TextView.setSectionHeaderText(resId: Int) {
    setSectionHeaderText(context.getText(resId))
}

/**
 * Приглашение терминала на главных экранах. В остальных стилях строка
 * скрыта и не занимает места.
 */
fun bindTerminalPrompt(row: View, text: TextView, promptRes: Int) {
    if (!ThemeManager.isTerminal(row.context)) {
        row.visibility = View.GONE
        return
    }
    row.visibility = View.VISIBLE
    applyTerminalTypeface(row)
    text.setText(promptRes)
}

/**
 * Моноширинный шрифт для всего поддерева. На Android 4.x встроенный
 * MONOSPACE — единственный гарантированно доступный моноширинный шрифт,
 * поэтому внешние файлы не нужны.
 */
fun applyTerminalTypeface(root: View) {
    if (!ThemeManager.isTerminal(root.context)) return
    if (root is TextView) {
        val current = root.typeface
        val style = when {
            current == null -> Typeface.NORMAL
            current.isBold -> Typeface.BOLD
            current.isItalic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
        root.typeface = Typeface.create(Typeface.MONOSPACE, style)
        return
    }
    if (root is ViewGroup) {
        for (index in 0 until root.childCount) {
            applyTerminalTypeface(root.getChildAt(index))
        }
    }
}

/**
 * Акцентная кнопка с состоянием нажатия.
 *
 * Штатная кнопка AppCompat на Android 4.x выглядит серой независимо от
 * темы, поэтому основные действия (вход) красим вручную. В терминальном
 * стиле кнопка становится контурной, а при нажатии заливается акцентом.
 */
fun accentButtonBackground(context: Context): StateListDrawable {
    val accent = ThemeManager.color(context, R.attr.yamusAccent)
    val pressed = ColorUtils.blendARGB(accent, Color.BLACK, 0.18f)
    val radius = if (ThemeManager.isFlat(context)) {
        0f
    } else {
        context.dp(6).toFloat()
    }

    fun shape(color: Int, stroke: Int = 0): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = radius
        setColor(color)
        if (stroke != 0) setStroke(context.dp(1).coerceAtLeast(1), stroke)
    }

    return StateListDrawable().apply {
        if (ThemeManager.isTerminal(context)) {
            // В покое — контур, при нажатии — лёгкая акцентная подсветка.
            val tint = Color.argb(0x33, Color.red(accent), Color.green(accent), Color.blue(accent))
            addState(intArrayOf(android.R.attr.state_pressed), shape(tint, accent))
            addState(intArrayOf(), shape(Color.TRANSPARENT, accent))
        } else {
            addState(intArrayOf(android.R.attr.state_pressed), shape(pressed))
            addState(intArrayOf(android.R.attr.state_enabled), shape(accent))
            addState(intArrayOf(), shape(accent))
        }
    }
}

/** Цвет текста акцентной кнопки; в терминале — всегда акцент. */
fun accentButtonTextColors(context: Context): ColorStateList =
    ColorStateList.valueOf(
        ThemeManager.color(
            context,
            if (ThemeManager.isTerminal(context)) R.attr.yamusAccent else R.attr.yamusOnAccent
        )
    )

/**
 * Диалоги живут в отдельном окне и не наследуют моноширинный шрифт
 * activity. В терминальном стиле делаем их прямоугольными, с акцентной
 * рамкой и тем же шрифтом.
 */
fun styleTerminalDialog(dialog: android.app.Dialog) {
    if (!ThemeManager.isTerminal(dialog.context)) return
    val window = dialog.window ?: return
    val context = dialog.context
    val accent = ThemeManager.color(context, R.attr.yamusAccent)
    val card = ThemeManager.color(context, R.attr.yamusCardElevated)
    val background = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = 0f
        setColor(card)
        setStroke(context.dp(1).coerceAtLeast(1), accent)
    }
    window.setBackgroundDrawable(InsetDrawable(background, context.dp(16)))
    // Строки списков создаются при первой раскладке, поэтому шрифт
    // применяем после неё.
    val decor = window.decorView
    decor.post { applyTerminalTypeface(decor) }
}
