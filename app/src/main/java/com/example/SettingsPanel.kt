package com.example

import android.os.Build
import android.view.ViewGroup
import androidx.appcompat.widget.AppCompatButton
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat

object SettingsPanel {
    fun show(activity: AppCompatActivity, updates: AppUpdates, muted: Boolean,
             onMute: (Boolean) -> Unit, onAppearance: () -> Unit): AlertDialog {
        val density = activity.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val body = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(4), dp(24), dp(12))
        }
        fun label(text: String, size: Float, color: Int = R.color.shell_secondary, top: Int = 0) {
            body.addView(TextView(activity).apply {
                this.text = text; textSize = size; setTextColor(ContextCompat.getColor(activity, color))
                setLineSpacing(dp(3).toFloat(), 1f)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
        }
        fun section(text: Int) { label(activity.getString(text), 11f, R.color.shell_secondary, 24) }
        fun toggle(title: Int, description: Int, initial: Boolean, change: (Boolean) -> Unit) {
            body.addView(SwitchCompat(activity).apply {
                id = when (title) {
                    R.string.mute_setting -> R.id.settingMute
                    R.string.solid_setting -> R.id.settingSolid
                    else -> R.id.settingAutomatic
                }
                text = activity.getString(title); textSize = 16f
                setTextColor(ContextCompat.getColor(activity, R.color.shell_text))
                minHeight = dp(56); switchPadding = dp(16)
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> change(checked) }
            }, LinearLayout.LayoutParams(-1, -2))
            label(activity.getString(description), 13f)
        }
        var dialog: AlertDialog? = null
        label(activity.getString(R.string.version_label, BuildConfig.VERSION_NAME), 13f)
        section(R.string.playback)
        toggle(R.string.mute_setting, R.string.mute_description, muted, onMute)
        section(R.string.appearance)
        val appearance = activity.getSharedPreferences("appearance", 0)
        toggle(R.string.solid_setting, R.string.solid_description, appearance.getBoolean("solid", false)) { checked ->
            appearance.edit().putBoolean("solid", checked).apply()
            onAppearance()
            // Immediate safe fallback in the open sheet; future sheets reread the preference.
            dialog?.window?.setBackgroundDrawableResource(R.drawable.shell_dialog_background)
            if (checked && Build.VERSION.SDK_INT >= 31) dialog?.window?.setBackgroundBlurRadius(0)
        }
        section(R.string.updates)
        toggle(R.string.automatic_setting, R.string.automatic_description, updates.automaticChecks(), updates::setAutomaticChecks)
        body.addView(AppCompatButton(activity).apply {
            id = R.id.checkUpdatesButton
            setText(R.string.check_updates)
            setOnClickListener { dialog?.dismiss(); updates.checkNow() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        if (updates.hasDownloadedUpdate()) body.addView(AppCompatButton(activity).apply {
            setText(R.string.install_downloaded)
            setOnClickListener { dialog?.dismiss(); updates.installPendingUpdate() }
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        label(activity.getString(R.string.discovery_label), 16f, R.color.shell_text, 28)
        label(activity.getString(R.string.discovery_description), 13f, top = 8)
        val scroll = ScrollView(activity).apply {
            isFillViewport = false
            addView(body, ViewGroup.LayoutParams(-1, -2))
        }
        dialog = AlertDialog.Builder(activity).setTitle(R.string.settings).setView(scroll)
            .setPositiveButton(R.string.close, null).showGlass(activity)
        return dialog
    }
}
