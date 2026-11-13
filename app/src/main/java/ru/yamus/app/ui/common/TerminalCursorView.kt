package ru.yamus.app.ui.common

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import ru.yamus.app.R

/**
 * Мигающий блок-курсор терминального стиля.
 *
 * Рисуется вручную, чтобы не зависеть от шрифта: на Android 4.x не во всех
 * системных моноширинных шрифтах есть символ «█». Анимация — обычный
 * ObjectAnimator, он есть с API 11.
 */
class TerminalCursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint()
    private var animator: ObjectAnimator? = null

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        paint.color = ThemeManager.color(context, R.attr.yamusAccent)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (!ThemeManager.isTerminal(context)) {
            alpha = 1f
            return
        }
        animator?.cancel()
        animator = ObjectAnimator.ofFloat(this, "alpha", 1f, 0f).apply {
            duration = 520L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}
