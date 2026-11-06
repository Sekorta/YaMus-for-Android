package ru.yamus.app.ui.common

import android.content.Context
import android.graphics.drawable.GradientDrawable
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
    background = circleDrawable(context, colorAttr)
}
