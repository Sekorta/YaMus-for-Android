package ru.yamus.app.ui.common

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View
import ru.yamus.app.R

/** View.setBackground появился только в API 16, а приложение работает с API 14. */
fun View.setBackgroundCompat(drawable: Drawable) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
        background = drawable
    } else {
        @Suppress("DEPRECATION")
        setBackgroundDrawable(drawable)
    }
}

/**
 * Фон для круглых (в современном стиле) элементов: кнопка play, аватары.
 *
 * CardView на Android 4.x добавляет внутренние отступы под тень и из-за
 * большого cardCornerRadius заметно раздувает размеры, поэтому такие
 * элементы рисуем обычным GradientDrawable. В стиле Android 4 фигура
 * становится прямоугольной — по радиусу из темы.
 */
fun View.setCircleBackground(context: Context, colorAttr: Int) {
    val drawable = GradientDrawable().apply {
        if (ThemeManager.isFlat(context)) {
            shape = GradientDrawable.RECTANGLE
            cornerRadius =
                ThemeManager.dimension(context, R.attr.yamusCornerSmall).toFloat()
        } else {
            shape = GradientDrawable.OVAL
        }
        setColor(ThemeManager.color(context, colorAttr))
    }
    setBackgroundCompat(drawable)
}
