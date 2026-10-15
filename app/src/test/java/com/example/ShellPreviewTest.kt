package com.example

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

class ShellPreviewActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_CleanGram)
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
    }
}

/** Real native layouts with a labelled WebView placeholder; never an Instagram account screenshot. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], qualifiers = "w390dp-h844dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShellPreviewTest {
    private fun capture(view: View, name: String, width: Int = 390, height: Int = 760) {
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, width, height)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        val file = File("build/outputs/shell-previews/$name.png")
        file.parentFile!!.mkdirs()
        FileOutputStream(file).use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        bitmap.recycle()
    }

    private fun shell(name: String, error: Boolean = false, width: Int = 390, height: Int = 760) {
        val controller = Robolectric.buildActivity(ShellPreviewActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<View>(R.id.rootContainer)
        activity.findViewById<View>(R.id.navHome).isSelected = true
        activity.findViewById<View>(R.id.loadingContainer).visibility = View.GONE
        if (error) {
            activity.findViewById<View>(R.id.webView).visibility = View.GONE
            activity.findViewById<View>(R.id.errorContainer).visibility = View.VISIBLE
        } else {
            val content = activity.findViewById<FrameLayout>(R.id.contentFrame)
            activity.findViewById<View>(R.id.webView).visibility = View.GONE
            content.addView(TextView(activity).apply {
                text = "INSTAGRAM WEBVIEW\n\nYour feed stays here.\n\nPosts, messages and profiles are\nrendered by Instagram.\n\nNative shell preview · no account data"
                gravity = android.view.Gravity.CENTER
                textSize = 16f
                setLineSpacing(8f, 1f)
                setTextColor(ContextCompat.getColor(activity, R.color.shell_secondary))
            }, 0, FrameLayout.LayoutParams(-1, -1))
        }
        capture(root, name, width, height)
        val toolbar = activity.findViewById<View>(R.id.toolbarBand)
        val content = activity.findViewById<View>(R.id.contentFrame)
        val dock = activity.findViewById<View>(R.id.bottomDock)
        assertTrue("Toolbar must reserve space above web content", toolbar.bottom <= content.top)
        assertTrue("Dock must reserve space below web content", content.bottom <= dock.top)
        assertTrue("Web content must have usable height", content.height > 100)
        for (id in listOf(R.id.settingsButton, R.id.soundButton, R.id.navHome, R.id.navMessages, R.id.navProfile)) {
            val control = activity.findViewById<View>(id)
            assertTrue("Touch target too short", control.height >= 48)
            assertTrue("Touch target too narrow", control.width >= 48)
        }
        controller.pause().stop().destroy()
    }

    @Test fun lightHome() = shell("home-light")
    @Test @Config(qualifiers = "w390dp-h844dp-night-mdpi") fun darkHome() = shell("home-dark")
    @Test fun enlargedText() {
        org.robolectric.RuntimeEnvironment.setFontScale(1.6f)
        try { shell("large-text") } finally { org.robolectric.RuntimeEnvironment.setFontScale(1f) }
    }
    @Test fun error() = shell("error-light", error = true)
    @Test @Config(qualifiers = "w780dp-h390dp-land-mdpi") fun landscape() = shell("landscape", width = 780, height = 340)
    @Test @Config(qualifiers = "w320dp-h640dp-mdpi") fun compact() = shell("compact", width = 320, height = 560)

    @Test fun settings() {
        val controller = Robolectric.buildActivity(ShellPreviewActivity::class.java).create()
        val activity = controller.get()
        val updates = AppUpdates(activity)
        controller.start().resume().visible()
        var sound: Boolean? = null
        var appearanceChanges = 0
        val dialog = SettingsPanel.show(activity, updates, true, { sound = it }, { appearanceChanges++ })
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
        val panel = dialog.findViewById<View>(android.R.id.content)!!
        // Offscreen Canvas capture has no window AttachInfo; native scroll
        // indicator arrows are window-owned, so omit them in this preview only.
        fun removeIndicators(view: View) {
            view.scrollIndicators = 0
            if (view is ViewGroup) for (index in 0 until view.childCount) removeIndicators(view.getChildAt(index))
        }
        removeIndicators(panel)
        panel.setBackgroundResource(R.drawable.shell_dialog_background)
        capture(panel, "settings-light", 358, 720)
        assertTrue(dialog.isShowing)
        val check = dialog.findViewById<android.widget.Button>(R.id.checkUpdatesButton)!!
        val displayText = check.transformationMethod?.getTransformation(check.text, check)?.toString() ?: check.text.toString()
        assertEquals("Check for updates", displayText)
        assertTrue(check.minHeight >= 48)
        dialog.findViewById<View>(R.id.settingMute)!!.performClick()
        assertEquals(false, sound)
        dialog.findViewById<View>(R.id.settingSolid)!!.performClick()
        assertTrue(activity.getSharedPreferences("appearance", 0).getBoolean("solid", false))
        assertEquals(1, appearanceChanges)
        dialog.findViewById<View>(R.id.settingAutomatic)!!.performClick()
        assertFalse(updates.automaticChecks())
        dialog.dismiss(); updates.close()
        controller.pause().stop().destroy()
    }
}
