package ru.yamus.app.ui.common

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.View

/**
 * Круглый фон, собранный программно.
 *
 * CardView на Android 4.x добавляет внутренние отступы под тень и из-за
 * большого cardCornerRadius заметно раздувает размеры, поэтому круглые
 * элементы (кнопка play, аватары) рисуем обычным GradientDrawable.
 */
fun circleDrawable(context: Context, colorAttr: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(ThemeManager.color(context, colorAttr))
    }

fun View.setCircleBackground(context: Context, colorAttr: Int) {
    val drawable = circleDrawable(context, colorAttr)
    // View.setBackground появился только в API 16, а приложение обещает
    // работать на Android 4.0/4.1 (API 14–15).
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN) {
        background = drawable
    } else {
        @Suppress("DEPRECATION")
        setBackgroundDrawable(drawable)
    }
}
