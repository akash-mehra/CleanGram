package com.example

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import androidx.core.graphics.ColorUtils
import android.util.AttributeSet
import android.widget.FrameLayout
import androidx.core.content.ContextCompat

/** Frosted tint and a restrained highlight; never blurs WebView text or media. */
class GlassSurface @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    init { refresh() }

    fun refresh() {
        val solid = context.getSharedPreferences("appearance", 0).getBoolean("solid", false)
        val fill = ContextCompat.getColor(context, if (solid) R.color.shell_surface else R.color.shell_panel)
        val surface = ContextCompat.getColor(context, R.color.shell_surface)
        val highlight = ContextCompat.getColor(context, R.color.shell_highlight)
        val glass = GradientDrawable(GradientDrawable.Orientation.TL_BR,
            if (solid) intArrayOf(surface, surface) else intArrayOf(
                ColorUtils.blendARGB(fill, highlight, 0.08f), fill, ColorUtils.setAlphaComponent(surface, 232))).apply {
            cornerRadius = 32 * resources.displayMetrics.density
            setStroke((resources.displayMetrics.density).toInt().coerceAtLeast(1),
                ContextCompat.getColor(context, R.color.shell_border))
        }
        val rim = GradientDrawable().apply {
            cornerRadius = 32 * resources.displayMetrics.density
            setColor(android.graphics.Color.TRANSPARENT)
            setStroke(resources.displayMetrics.density.toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(highlight, if (solid) 80 else 160))
        }
        background = LayerDrawable(arrayOf(glass, rim))
        clipToOutline = true
    }
}
