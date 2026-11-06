package ru.yamus.app.ui.common

import android.content.Context
import android.util.AttributeSet
import androidx.appcompat.content.res.AppCompatResources
import androidx.appcompat.widget.AppCompatImageView
import ru.yamus.app.R

/**
 * ImageView для векторных иконок: умеет красить себя через атрибут yamusTint
 * и безопасно грузит vector drawable на Android 4.x (через AppCompat).
 */
class IconView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : AppCompatImageView(context, attrs, defStyleAttr) {

    init {
        scaleType = ScaleType.FIT_CENTER
        if (attrs != null) {
            val typed = context.obtainStyledAttributes(attrs, R.styleable.IconView)
            val color = typed.getColor(R.styleable.IconView_yamusTint, 0)
            typed.recycle()
            if (color != 0) setColorFilter(color)
        }
    }

    fun setTintColor(color: Int) {
        setColorFilter(color)
    }

    override fun setImageResource(resId: Int) {
        if (resId == 0) {
            super.setImageResource(0)
            return
        }
        val drawable = AppCompatResources.getDrawable(context, resId)
        if (drawable != null) {
            setImageDrawable(drawable)
        } else {
            super.setImageResource(resId)
        }
    }
}
