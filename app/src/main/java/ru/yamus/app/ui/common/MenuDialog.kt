package ru.yamus.app.ui.common

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import ru.yamus.app.R
import ru.yamus.app.core.util.dp

/**
 * Меню «три точки» с группами пунктов и разделителями между ними.
 *
 * Штатный `setItems` у AlertDialog рисует плоский список без возможности
 * вставить разделитель, поэтому пункты собираются вручную. Заодно это даёт
 * одинаковый вид меню трека, альбома и исполнителя на всех версиях Android,
 * включая 4.x.
 */
object MenuDialog {

    class Item(val title: CharSequence, val action: () -> Unit)

    fun show(context: Context, title: CharSequence?, groups: List<List<Item>>) {
        val visibleGroups = groups.filter { it.isNotEmpty() }
        if (visibleGroups.isEmpty()) return

        val textColor = ThemeManager.color(context, R.attr.yamusText)
        val dividerColor = ThemeManager.color(context, R.attr.yamusDivider)
        val rowBackground = selectableBackground(context)

        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }

        var dialog: AlertDialog? = null

        visibleGroups.forEachIndexed { groupIndex, group ->
            if (groupIndex > 0) {
                container.addView(
                    View(context).apply {
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            context.dp(1).coerceAtLeast(1)
                        )
                        setBackgroundColor(dividerColor)
                    }
                )
            }
            for (item in group) {
                val row = TextView(context).apply {
                    text = item.title
                    setTextColor(textColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTypeface(typeface, Typeface.NORMAL)
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    if (rowBackground != 0) setBackgroundResource(rowBackground)
                    // Отступы задаются после фона: на старых версиях Android
                    // установка фона сбрасывала ранее выставленный padding.
                    setPadding(context.dp(20), context.dp(14), context.dp(20), context.dp(14))
                    setOnClickListener {
                        dialog?.dismiss()
                        item.action()
                    }
                }
                container.addView(
                    row,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    )
                )
            }
        }

        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(container)
        }

        dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Системный фон нажатия из темы, чтобы строки «отзывались» на касание. */
    private fun selectableBackground(context: Context): Int {
        val value = TypedValue()
        val resolved = context.theme.resolveAttribute(
            android.R.attr.selectableItemBackground,
            value,
            true
        )
        return if (resolved) value.resourceId else 0
    }
}
