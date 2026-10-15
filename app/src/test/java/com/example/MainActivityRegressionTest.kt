package com.example

import android.content.Context
import android.os.Bundle
import android.view.View
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Real Activity smoke tests use Robolectric's WebView, not a network or Instagram account. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class MainActivityRegressionTest {
    @Before fun disableNetworkUpdateChecks() {
        ApplicationProvider.getApplicationContext<Context>().getSharedPreferences("updates", 0)
            .edit().putBoolean("automatic", false).commit()
    }

    @Test fun `sound preference and Instagram cookie survive shell recreation`() {
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setCookie(MainActivity.DEFAULT_URL, "cleangram_test=preserved")
        val first = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = first.get()
        val button = activity.findViewById<View>(R.id.soundButton)
        val old = activity.getPreferences(Context.MODE_PRIVATE).getBoolean("muted", true)
        button.performClick()
        assertEquals(!old, activity.getPreferences(Context.MODE_PRIVATE).getBoolean("muted", old))
        val state = Bundle()
        first.saveInstanceState(state).pause().stop().destroy()
        val second = Robolectric.buildActivity(MainActivity::class.java).create(state).start().resume().visible()
        assertEquals(!old, second.get().getPreferences(Context.MODE_PRIVATE).getBoolean("muted", old))
        assertTrue(cookies.getCookie(MainActivity.DEFAULT_URL).contains("cleangram_test=preserved"))
        second.pause().stop().destroy()
    }

    @Test fun `history callback synchronizes native selection for SPA messages and profiles`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.webView)
        web.webViewClient.doUpdateVisitedHistory(web, "https://www.instagram.com/direct/t/123/", false)
        assertTrue(activity.findViewById<View>(R.id.navMessages).isSelected)
        web.webViewClient.doUpdateVisitedHistory(web, "https://www.instagram.com/my.account/", false)
        assertTrue(activity.findViewById<View>(R.id.navProfile).isSelected)
        web.webViewClient.doUpdateVisitedHistory(web, "https://www.instagram.com/accounts/activity/", false)
        assertTrue(activity.findViewById<View>(R.id.navNotifications).isSelected)
        web.webViewClient.doUpdateVisitedHistory(web, MainActivity.DEFAULT_URL, false)
        assertTrue(activity.findViewById<View>(R.id.navHome).isSelected)
        controller.pause().stop().destroy()
    }

    @Test fun `fullscreen has a separate media host and exits through a native control`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.webView)
        var exits = 0
        web.webChromeClient!!.onShowCustomView(View(activity), WebChromeClient.CustomViewCallback { exits++ })
        assertEquals(View.GONE, activity.findViewById<View>(R.id.mainShell).visibility)
        assertEquals(1, activity.findViewById<FrameLayout>(R.id.fullscreenMedia).childCount)
        activity.findViewById<View>(R.id.exitFullscreenButton).performClick()
        assertEquals(1, exits)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.mainShell).visibility)
        assertEquals(0, activity.findViewById<FrameLayout>(R.id.fullscreenMedia).childCount)
        controller.pause().stop().destroy()
    }

    @Test fun `keyboard hides both floating controls without stacking bottom insets`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val root = activity.findViewById<View>(R.id.rootContainer)
        fun dispatch(keyboard: Boolean) {
            val insets = androidx.core.view.WindowInsetsCompat.Builder()
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars(), androidx.core.graphics.Insets.of(0, 24, 0, 24))
                .setInsets(androidx.core.view.WindowInsetsCompat.Type.ime(), androidx.core.graphics.Insets.of(0, 0, 0, if (keyboard) 300 else 0))
                .setVisible(androidx.core.view.WindowInsetsCompat.Type.ime(), keyboard).build()
            androidx.core.view.ViewCompat.dispatchApplyWindowInsets(root, insets)
        }
        dispatch(true)
        assertEquals(300, root.paddingBottom)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.bottomDock).visibility)
        assertEquals(View.GONE, activity.findViewById<View>(R.id.toolbarBand).visibility)
        dispatch(false)
        assertEquals(24, root.paddingBottom)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.bottomDock).visibility)
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.toolbarBand).visibility)
        controller.pause().stop().destroy()
    }

    @Test fun `Instagram account responses remain in WebView while server errors show retry`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        val activity = controller.get()
        val web = activity.findViewById<WebView>(R.id.webView)
        val request = object : android.webkit.WebResourceRequest {
            override fun getUrl() = android.net.Uri.parse("https://www.instagram.com/accounts/login/")
            override fun isForMainFrame() = true
            override fun isRedirect() = false
            override fun hasGesture() = true
            override fun getMethod() = "GET"
            override fun getRequestHeaders() = emptyMap<String, String>()
        }
        fun response(code: Int) = android.webkit.WebResourceResponse("text/html", "UTF-8", code, "Response",
            emptyMap(), java.io.ByteArrayInputStream(byteArrayOf()))
        web.webViewClient.onReceivedHttpError(web, request, response(403))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.errorContainer).visibility)
        web.webViewClient.onReceivedHttpError(web, request, response(503))
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.errorContainer).visibility)
        assertEquals(View.GONE, web.visibility)
        controller.pause().stop().destroy()
    }

}
