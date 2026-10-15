package com.example

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

/** Frosted tint and a restrained highlight; never blurs WebView text or media. */
class GlassSurface @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    init { refresh() }

    fun refresh() {
        val solid = context.getSharedPreferences("appearance", 0).getBoolean("solid", false)
        val fill = ContextCompat.getColor(context, if (solid) R.color.shell_surface else R.color.shell_panel)
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            intArrayOf(fill, ContextCompat.getColor(context, R.color.shell_surface))).apply {
            cornerRadius = 26 * resources.displayMetrics.density
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1),
                ContextCompat.getColor(context, R.color.shell_border))
        }
        clipToOutline = true
    }
}
