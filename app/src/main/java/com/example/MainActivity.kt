package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import android.media.AudioManager
import android.content.res.ColorStateList
import androidx.core.content.ContextCompat
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import androidx.webkit.ScriptHandler
import org.json.JSONObject
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.FrameLayout
import androidx.core.view.WindowInsetsControllerCompat
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorContainer: View
    private lateinit var retryButton: Button

    private lateinit var updates: AppUpdates
    private lateinit var soundButton: ImageButton
    private lateinit var fullscreenSoundButton: ImageButton
    private lateinit var mainShell: View
    private lateinit var bottomDock: View
    private lateinit var loadingContainer: View
    private lateinit var fullscreenMedia: FrameLayout
    private var keyboardVisible = false
    private var pageError = false
    private var lastRequestedUrl = DEFAULT_URL
    private var documentStartScript: ScriptHandler? = null
    private var selectedDestination = ShellNavigation.Destination.HOME
    private val blockingScript by lazy { assets.open("distraction-blocking.js").bufferedReader().use { it.readText() } }
    private val shellScript by lazy { assets.open("instagram-shell.js").bufferedReader().use { it.readText() } }
    private val mediaScript by lazy { assets.open("media-preferences.js").bufferedReader().use { it.readText() } }
    private lateinit var fullscreenContainer: View
    private var customView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var muted = true

    private var filePathCallback: ValueCallback<Array<Uri>>? = null

    // Activity result launcher for web file chooser / photo upload dialogs
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val intent = result.data
            val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, intent)
            filePathCallback?.onReceiveValue(uris)
        } else {
            filePathCallback?.onReceiveValue(null)
        }
        filePathCallback = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        volumeControlStream = AudioManager.STREAM_MUSIC

        val rootContainer = findViewById<View>(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorContainer = findViewById(R.id.errorContainer)
        retryButton = findViewById(R.id.retryButton)
        soundButton = findViewById(R.id.soundButton)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        mainShell = findViewById(R.id.mainShell)
        bottomDock = findViewById(R.id.bottomDock)
        loadingContainer = findViewById(R.id.loadingContainer)
        fullscreenMedia = findViewById(R.id.fullscreenMedia)
        fullscreenSoundButton = findViewById(R.id.fullscreenSoundButton)
        lastRequestedUrl = savedInstanceState?.getString("lastRequestedUrl") ?: DEFAULT_URL
        muted = getPreferences(MODE_PRIVATE).getBoolean("muted", true)
        updateSoundButton()
        soundButton.setOnClickListener { setMuted(!muted) }
        fullscreenSoundButton.setOnClickListener { setMuted(!muted) }
        findViewById<View>(R.id.exitFullscreenButton).setOnClickListener { exitFullscreen() }

        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            keyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            val layout = ShellLayout.insets(safe.left, safe.top, safe.right, safe.bottom, ime.bottom,
                keyboardVisible, customView != null)
            view.setPadding(layout.left, layout.top, layout.right, layout.bottom)
            bottomDock.visibility = if (layout.showDock) View.VISIBLE else View.GONE
            insets
        }
        ViewCompat.requestApplyInsets(rootContainer)
        // Centre the dock on large displays without stretching the touch targets.
        rootContainer.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val panel = findViewById<View>(R.id.navigationPanel)
            val maxWidth = (480 * resources.displayMetrics.density).toInt()
            val available = rootContainer.width - rootContainer.paddingLeft - rootContainer.paddingRight - (40 * resources.displayMetrics.density).toInt()
            val width = minOf(maxWidth, available).coerceAtLeast(0)
            if (width > 0 && panel.layoutParams.width != width) {
                panel.layoutParams = panel.layoutParams.apply { this.width = width }
            }
        }
        updates = AppUpdates(this)
        findViewById<View>(R.id.settingsButton).setOnClickListener {
            SettingsPanel.show(this, updates, muted, ::setMuted) { refreshSurfaces() }
        }
        bindNavigation()

        setupWebView()
        setupBackNavigation()
        installDocumentStartScript()

        retryButton.setOnClickListener {
            pageError = false
            loadingContainer.visibility = View.VISIBLE
            errorContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.loadUrl(lastRequestedUrl)
        }

        if (savedInstanceState != null) {
            if (webView.restoreState(savedInstanceState) == null) webView.loadUrl(DEFAULT_URL)
        } else {
            webView.loadUrl(DEFAULT_URL)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // 1. Session persistence via CookieManager
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // 2. WebSettings configuration
        @Suppress("DEPRECATION")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            setSupportZoom(true)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = false
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            WebViewCompat.addWebMessageListener(webView, "CleanGramShell",
                setOf("https://www.instagram.com", "https://instagram.com")) { _, message, origin, mainFrame, _ ->
                if (mainFrame && ShellNavigation.isTrustedPage(origin.toString())) {
                    val path = runCatching { JSONObject(message.data ?: "{}").optString("path") }.getOrDefault("")
                    if (path.length in 1..512 && path.startsWith("/") && !path.startsWith("//")) {
                        updateNativeRoute(origin.toString().trimEnd('/') + path)
                    }
                }
            }
        }

        // 3. WebChromeClient for file uploads, photo pickers, alerts, and progress
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenMedia.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullscreenContainer.visibility = View.VISIBLE
                mainShell.visibility = View.GONE
                ViewCompat.requestApplyInsets(findViewById(R.id.rootContainer))
                WindowInsetsControllerCompat(window, fullscreenContainer).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
                }
            }

            override fun onHideCustomView() = exitFullscreen()

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.INVISIBLE
                }
            }

            override fun onShowFileChooser(
                view: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }

                return try {
                    filePickerLauncher.launch(intent)
                    true
                } catch (e: Exception) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    false
                }
            }

            override fun onJsAlert(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setOnCancelListener { result?.cancel() }
                    .showGlass(this@MainActivity)
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .showGlass(this@MainActivity)
                return true
            }

            override fun onJsPrompt(
                view: WebView?,
                url: String?,
                message: String?,
                defaultValue: String?,
                result: JsPromptResult?
            ): Boolean {
                val input = EditText(this@MainActivity).apply {
                    setText(defaultValue)
                }
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setView(input)
                    .setPositiveButton(android.R.string.ok) { _, _ ->
                        result?.confirm(input.text.toString())
                    }
                    .setNegativeButton(android.R.string.cancel) { _, _ ->
                        result?.cancel()
                    }
                    .setOnCancelListener { result?.cancel() }
                    .showGlass(this@MainActivity)
                return true
            }
        }

        // 4. WebViewClient for URL overrides and CSS/JS distraction-free injection
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val uri = request?.url ?: return false
                return handleUrlNavigation(view, uri)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlNavigation(view, Uri.parse(url))
            }

            private fun handleUrlNavigation(view: WebView?, uri: Uri): Boolean {
                val urlString = uri.toString()

                // Block or drop accidental direct navigation to Reels and redirect back to main Instagram feed
                if (isDistractionUrl(urlString)) {
                    view?.loadUrl(DEFAULT_URL)
                    return true
                }

                // Keep all Instagram domain navigation internal to the WebView
                val host = uri.host?.lowercase(Locale.ROOT)
                if (isInstagramHost(host)) {
                    return false
                }

                // Only hand off web schemes; ignore intent:, file:, javascript: etc. from page content
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                if (scheme != "http" && scheme != "https") return true

                // Open external non-Instagram links in an external browser Intent
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    startActivity(intent)
                } catch (_: Exception) {
                    // Fallback or ignore if no external handler available
                }
                return true
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (url != null && isDistractionUrl(url)) {
                    view?.loadUrl(DEFAULT_URL)
                    return
                }
                lastRequestedUrl = url ?: lastRequestedUrl
                pageError = false
                errorContainer.visibility = View.GONE
                loadingContainer.visibility = View.VISIBLE
                webView.visibility = View.VISIBLE
                updateNativeRoute(url)
                injectPageScripts(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectPageScripts(view)
                loadingContainer.visibility = View.GONE
                updateNativeRoute(url)
                CookieManager.getInstance().flush()
            }

            override fun onPageCommitVisible(view: WebView?, url: String?) {
                super.onPageCommitVisible(view, url)
                injectPageScripts(view)
                loadingContainer.visibility = View.GONE
            }

            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                super.doUpdateVisitedHistory(view, url, isReload)
                updateNativeRoute(url)
            }

            override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, response: android.webkit.WebResourceResponse?) {
                super.onReceivedHttpError(view, request, response)
                if (request?.isForMainFrame == true && (response?.statusCode ?: 0) >= 400) showPageError()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    showPageError()
                }
            }
        }
    }

    private fun installDocumentStartScript() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return
        documentStartScript?.remove()
        documentStartScript = WebViewCompat.addDocumentStartJavaScript(webView,
            "if (window.top === window) {\n" + blockingScript + "\n" + shellScript +
                "\nwindow.__cleangramMuted = $muted;\n" + mediaScript + "\n}",
            setOf("https://www.instagram.com", "https://instagram.com"))
    }

    private fun injectPageScripts(view: WebView?) {
        if (!ShellNavigation.isTrustedPage(view?.url)) return
        view?.evaluateJavascript(blockingScript, null)
        view?.evaluateJavascript(shellScript, null)
        applySoundPreference()
    }

    private fun setMuted(value: Boolean) {
        muted = value
        getPreferences(MODE_PRIVATE).edit().putBoolean("muted", muted).apply()
        updateSoundButton()
        applySoundPreference()
        installDocumentStartScript()
    }

    private fun updateSoundButton() {
        for (button in listOf(soundButton, fullscreenSoundButton)) {
            button.setImageResource(if (muted) R.drawable.ic_sound_off else R.drawable.ic_sound_on)
            button.contentDescription = getString(if (muted) R.string.unmute_video else R.string.mute_video)
            ViewCompat.setStateDescription(button, getString(if (muted) R.string.sound_off else R.string.sound_on))
        }
    }

    private fun applySoundPreference() {
        if (ShellNavigation.isTrustedPage(webView.url)) {
            webView.evaluateJavascript("window.__cleangramMuted = $muted;\n" + mediaScript, null)
        }
    }

    private fun bindNavigation() {
        for ((id, destination) in listOf(R.id.navHome to ShellNavigation.Destination.HOME,
            R.id.navMessages to ShellNavigation.Destination.MESSAGES, R.id.navProfile to ShellNavigation.Destination.PROFILE)) {
            findViewById<View>(id).apply {
                setOnClickListener { navigateNative(destination) }
                ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                    override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                        super.onInitializeAccessibilityNodeInfo(host, info)
                        info.className = "android.widget.RadioButton"
                        info.isCheckable = true
                        info.isChecked = host.isSelected
                    }
                })
            }
        }
        renderDestination(selectedDestination)
    }

    private fun renderDestination(destination: ShellNavigation.Destination) {
        val changed = selectedDestination != destination
        selectedDestination = destination
        findViewById<TextView>(R.id.screenTitle).setText(destination.label)
        val ids = listOf(R.id.navHome, R.id.navMessages, R.id.navProfile)
        val icons = listOf(R.id.navHomeIcon, R.id.navMessagesIcon, R.id.navProfileIcon)
        ids.forEachIndexed { index, id ->
            val selected = index == destination.ordinal
            findViewById<View>(id).isSelected = selected
            if (selected && changed) findViewById<View>(icons[index]).apply {
                animate().cancel()
                scaleX = 0.94f; scaleY = 0.94f
                animate().scaleX(1f).scaleY(1f).setDuration(140).start()
            }
            findViewById<ImageView>(icons[index]).imageTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this, if (selected) R.color.shell_accent else R.color.shell_secondary))
        }
    }

    private fun updateNativeRoute(url: String?) {
        if (url != null && isDistractionUrl(url)) {
            webView.loadUrl(DEFAULT_URL)
            return
        }
        ShellNavigation.destination(url)?.let(::renderDestination)
    }

    private fun navigateNative(destination: ShellNavigation.Destination) {
        if (customView != null) exitFullscreen()
        if (pageError) {
            if (destination.path != null) webView.loadUrl(ShellNavigation.ORIGIN + destination.path)
            else showProfileShortcut()
            return
        }
        if (!ShellNavigation.isTrustedPage(webView.url)) {
            webView.loadUrl(DEFAULT_URL)
            return
        }
        injectPageScripts(webView)
        val name = destination.name.lowercase(Locale.ROOT)
        webView.evaluateJavascript("!!(window.__cleanGramShell && window.__cleanGramShell.navigate('$name'))") { handled ->
            if (handled != "true") {
                if (destination.path != null) webView.loadUrl(ShellNavigation.ORIGIN + destination.path)
                else showProfileShortcut()
            }
        }
    }

    private fun showProfileShortcut() {
        val input = EditText(this).apply {
            hint = getString(R.string.username_hint)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            setSingleLine()
            contentDescription = getString(R.string.username_hint)
        }
        val dialog = AlertDialog.Builder(this).setTitle(R.string.profile_shortcut_title)
            .setMessage(R.string.profile_shortcut_body).setView(input)
            .setPositiveButton(R.string.open_profile, null).setNegativeButton(android.R.string.cancel, null).showGlass(this)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val path = ShellNavigation.profilePath(input.text.toString())
            if (path == null) input.error = getString(R.string.invalid_username)
            else { webView.loadUrl(ShellNavigation.ORIGIN + path); dialog.dismiss() }
        }
    }

    private fun showPageError() {
        pageError = true
        loadingContainer.visibility = View.GONE
        progressBar.visibility = View.INVISIBLE
        webView.visibility = View.GONE
        errorContainer.visibility = View.VISIBLE
    }

    private fun refreshSurfaces() {
        fun refresh(view: View) {
            if (view is GlassSurface) view.refresh()
            if (view is ViewGroup) for (i in 0 until view.childCount) refresh(view.getChildAt(i))
        }
        refresh(findViewById(R.id.rootContainer))
    }

    private fun exitFullscreen() {
        if (customView == null) return
        fullscreenMedia.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        mainShell.visibility = View.VISIBLE
        bottomDock.visibility = if (keyboardVisible) View.GONE else View.VISIBLE
        customView = null
        webView.visibility = if (pageError) View.GONE else View.VISIBLE
        ViewCompat.requestApplyInsets(findViewById(R.id.rootContainer))
        val callback = customViewCallback
        customViewCallback = null
        WindowInsetsControllerCompat(window, webView).show(WindowInsetsCompat.Type.systemBars())
        callback?.onCustomViewHidden()
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (customView != null) {
                    exitFullscreen()
                } else if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        for ((id, dimension) in listOf(R.id.toolbarBand to R.dimen.shell_toolbar_band_height,
            R.id.toolbarPanel to R.dimen.shell_toolbar_height, R.id.bottomDock to R.dimen.shell_dock_band_height,
            R.id.navigationPanel to R.dimen.shell_nav_height)) {
            findViewById<View>(id).apply { layoutParams = layoutParams.apply { height = resources.getDimensionPixelSize(dimension) } }
        }
        ViewCompat.requestApplyInsets(findViewById(R.id.rootContainer))
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
        outState.putString("lastRequestedUrl", lastRequestedUrl)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        if (customView == null) updates.onResume()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        if (::updates.isInitialized) updates.close()
        if (::webView.isInitialized) {
            exitFullscreen()
            documentStartScript?.remove()
            filePathCallback?.onReceiveValue(null)
            filePathCallback = null
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_URL = ShellNavigation.ORIGIN + "/"
        fun isInstagramHost(host: String?) = ShellNavigation.isInstagramHost(host)
        fun isReelsUrl(url: String) = ShellNavigation.isReelsUrl(url)
        fun isDistractionUrl(url: String) = ShellNavigation.isDistractionUrl(url)
    }
}
