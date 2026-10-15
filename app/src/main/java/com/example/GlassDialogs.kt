package com.example

import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.WindowManager
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.function.Consumer
import kotlin.math.min

/** A single dialog treatment, with an opaque fallback when cross-window blur is unavailable. */
object GlassDialogs {
    fun style(dialog: AlertDialog, activity: AppCompatActivity): AlertDialog {
        val window = dialog.window ?: return dialog
        val density = activity.resources.displayMetrics.density
        window.setLayout(min(activity.resources.displayMetrics.widthPixels - (32 * density).toInt(),
            (560 * density).toInt()), WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.CENTER)
        window.setDimAmount(0.32f)
        val solid = activity.getSharedPreferences("appearance", 0).getBoolean("solid", false)
        fun background(blur: Boolean) {
            window.setBackgroundDrawable(GradientDrawable().apply {
                setColor(ContextCompat.getColor(activity, if (blur && !activity.getSharedPreferences("appearance", 0).getBoolean("solid", false)) R.color.shell_panel else R.color.shell_surface))
                cornerRadius = 28 * density
                setStroke(density.toInt().coerceAtLeast(1), ContextCompat.getColor(activity, R.color.shell_border))
            })
        }
        background(false)
        if (Build.VERSION.SDK_INT >= 31 && !solid) {
            val listener = Consumer<Boolean> { enabled ->
                background(enabled)
                window.setBackgroundBlurRadius(if (enabled) (20 * density).toInt() else 0)
            }
            activity.windowManager.addCrossWindowBlurEnabledListener(listener)
            dialog.setOnDismissListener { activity.windowManager.removeCrossWindowBlurEnabledListener(listener) }
        }
        for (which in intArrayOf(AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL)) {
            dialog.getButton(which)?.apply { minHeight = (48 * density).toInt(); isAllCaps = false }
        }
        return dialog
    }
}

fun AlertDialog.Builder.showGlass(activity: AppCompatActivity): AlertDialog = show().also { GlassDialogs.style(it, activity) }
