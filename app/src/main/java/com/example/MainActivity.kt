package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
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
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorContainer: View
    private lateinit var loadingOverlay: View
    private lateinit var orbView: OrbLoadingView
    private lateinit var retryButton: Button
    private lateinit var updateBar: View
    private lateinit var updateButton: Button

    private var loadingVisible = false
    private var loadingShownAt = 0L

    /** Set only when this WebView can't run scripts at document start. */
    private var fallbackFilterScript: String? = null

    /** A load that never finishes shows the retry screen instead of spinning forever. */
    private val loadTimeout = Runnable {
        if (loadingVisible) {
            webView.stopLoading()
            showError()
        }
    }

    private var pendingUpdate: Update? = null
    private var updateDownloadId: Long = -1L

    /** DownloadManager announces completion by broadcast; hand the file to the installer. */
    private val downloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id != -1L && id == updateDownloadId) {
                updateDownloadId = -1L
                // Re-arm the button: if the install is cancelled or refused, tapping retries.
                updateButton.isEnabled = true
                updateButton.setText(R.string.update_action)
                Updater.install(this@MainActivity, id)
            }
        }
    }

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

        val rootContainer = findViewById<View>(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorContainer = findViewById(R.id.errorContainer)
        loadingOverlay = findViewById(R.id.loadingOverlay)
        orbView = findViewById(R.id.orbView)
        retryButton = findViewById(R.id.retryButton)
        updateBar = findViewById(R.id.updateBar)
        updateButton = findViewById(R.id.updateButton)
        applyContentWidth()

        // Inset for system bars AND display cutouts, so nothing sits under a notch or under
        // the landscape navigation bar. The keyboard is folded into the bottom inset, otherwise
        // it covers whatever field the page has focused.
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            insets
        }

        setupWebView()
        setupBackNavigation()

        retryButton.setOnClickListener {
            hideError()
            showLoadingOverlay()
            if (webView.url.isNullOrBlank()) webView.loadUrl(DEFAULT_URL) else webView.reload()
        }

        showLoadingOverlay()
        // restoreState returns null when there was nothing to restore; load the feed then,
        // or the loader would sit there until the timeout.
        if (savedInstanceState == null || webView.restoreState(savedInstanceState) == null) {
            webView.loadUrl(DEFAULT_URL)
        }

        setupUpdates()
    }

    private fun setupUpdates() {
        ContextCompat.registerReceiver(
            this,
            downloadComplete,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            // Sent by the system, so the receiver has to be visible outside the app.
            ContextCompat.RECEIVER_EXPORTED
        )

        updateButton.setOnClickListener {
            val update = pendingUpdate ?: return@setOnClickListener

            // Sideloading requires the user to allow this app to install packages.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            ) {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
                return@setOnClickListener
            }

            updateButton.isEnabled = false
            updateButton.setText(R.string.update_downloading)
            updateDownloadId = Updater.enqueueDownload(this, update)
        }

        // A failed check is not worth reporting: the app works regardless.
        lifecycleScope.launch {
            val update = Updater.findUpdate() ?: return@launch
            pendingUpdate = update
            updateBar.alpha = 0f
            updateBar.visibility = View.VISIBLE
            updateBar.animate().alpha(1f).setDuration(200)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        // Paint the themed surface behind the page so loads don't flash white in dark mode.
        webView.setBackgroundColor(ContextCompat.getColor(this, R.color.surface))

        // 1. Session persistence via CookieManager
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // Chrome on Android sends a "reduced" user agent with a frozen OS version and no device
        // model. The WebView's own string additionally carries "Version/4.0" and a Build/ tag,
        // which mark it as an embedded browser. Match Chrome exactly, keeping its major version.
        val chromeMajor = WebViewCompat.getCurrentWebViewPackage(this)?.versionName
            ?.substringBefore('.')
            ?.takeIf { it.isNotEmpty() }

        // 2. WebSettings configuration
        @Suppress("DEPRECATION")
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            // App, not a document: an accidental pinch leaves the whole layout zoomed and janky.
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            useWideViewPort = true
            loadWithOverviewMode = true
            mediaPlaybackRequiresUserGesture = false
            userAgentString = if (chromeMajor != null) {
                "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$chromeMajor.0.0.0 Mobile Safari/537.36"
            } else {
                userAgentString.replace("; wv", "")
            }
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        }

        // Dark mode: the theme is DayNight, so isLightTheme flips with the system setting and
        // the WebView asks the page for its dark styling (Instagram honours prefers-color-scheme),
        // darkening algorithmically where the page has none.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(webView.settings, true)
        }

        installPageFilter()

        // Android tags every WebView request with "X-Requested-With: <package>", which sites
        // use to detect an embedded browser. Send it to no origin at all.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(webView.settings, emptySet())
        }

        // 3. WebChromeClient for file uploads, photo pickers, alerts, and progress
        webView.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                progressBar.progress = newProgress
                if (newProgress < 100) {
                    if (progressBar.visibility != View.VISIBLE) {
                        progressBar.animate().cancel()
                        progressBar.alpha = 1f
                        progressBar.visibility = View.VISIBLE
                    }
                } else if (progressBar.visibility == View.VISIBLE) {
                    progressBar.animate()
                        .alpha(0f)
                        .setDuration(220)
                        .withEndAction { progressBar.visibility = View.GONE }
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
                    .create()
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView?, url: String?, message: String?, result: JsResult?): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setTitle(R.string.app_name)
                    .setMessage(message)
                    .setPositiveButton(android.R.string.ok) { _, _ -> result?.confirm() }
                    .setNegativeButton(android.R.string.cancel) { _, _ -> result?.cancel() }
                    .setOnCancelListener { result?.cancel() }
                    .create()
                    .show()
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
                    .create()
                    .show()
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
                // Instagram redirects to intent:// to force a hand-off into its native app. This
                // must be checked before the host check below, because those URLs sit on
                // instagram.com hosts and the WebView cannot load a non-web scheme. Swallow them
                // and stay on the web version, which is the whole point of this app.
                val scheme = uri.scheme?.lowercase(Locale.ROOT)
                if (scheme != "http" && scheme != "https") return true

                val urlString = uri.toString()

                // Block or drop accidental direct navigation to Reels and redirect back to the Following feed
                if (isReelsUrl(urlString)) {
                    // Loading from inside this callback races the navigation being cancelled.
                    view?.post { view.loadUrl(DEFAULT_URL) }
                    return true
                }

                // Keep all Instagram domain navigation internal to the WebView
                val host = uri.host?.lowercase(Locale.ROOT)
                if (isInstagramHost(host)) {
                    return false
                }

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
                hideError()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                fallbackFilterScript?.let { view?.evaluateJavascript(it, null) }
                // Fallback for pages with no posts, or WebViews without the message bridge.
                hideLoadingOverlay()
                CookieManager.getInstance().flush()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true) return

                // A navigation we replaced ourselves (e.g. the Reels redirect) is reported as an
                // unsupported-scheme error; that is not a connectivity failure.
                if (error?.errorCode == ERROR_UNSUPPORTED_SCHEME) return

                showError()
            }
        }
    }

    private fun showLoadingOverlay() {
        webView.removeCallbacks(loadTimeout)
        loadingOverlay.animate().cancel()
        loadingOverlay.alpha = 1f
        loadingOverlay.visibility = View.VISIBLE
        orbView.start()
        loadingVisible = true
        loadingShownAt = SystemClock.uptimeMillis()
        webView.postDelayed(loadTimeout, LOAD_TIMEOUT_MS)
    }

    private fun hideLoadingOverlay(immediately: Boolean = false) {
        webView.removeCallbacks(loadTimeout)
        if (!loadingVisible) return
        loadingVisible = false

        if (immediately) {
            loadingOverlay.animate().cancel()
            loadingOverlay.visibility = View.GONE
            orbView.stop()
            return
        }
        // A fast (cached) load would otherwise flash the loader for a single frame.
        val remaining = MIN_LOADING_MS - (SystemClock.uptimeMillis() - loadingShownAt)
        loadingOverlay.animate()
            .setStartDelay(remaining.coerceAtLeast(0L))
            .alpha(0f)
            .setDuration(280)
            .withEndAction {
                loadingOverlay.visibility = View.GONE
                orbView.stop()
            }
    }

    private fun showError() {
        hideLoadingOverlay(immediately = true)
        if (errorContainer.visibility == View.VISIBLE) return
        progressBar.visibility = View.GONE
        webView.visibility = View.GONE
        errorContainer.alpha = 0f
        errorContainer.visibility = View.VISIBLE
        errorContainer.animate().alpha(1f).setDuration(180)
    }

    private fun hideError() {
        errorContainer.animate().cancel()
        errorContainer.visibility = View.GONE
        webView.visibility = View.VISIBLE
    }

    private fun installPageFilter() {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            // The filter posts "ready" once posts (or the login form) are on the page, so the
            // loader hands straight over to content instead of to Instagram's own splash and
            // skeleton. Only Instagram's origins get the bridge, and it can only do this.
            WebViewCompat.addWebMessageListener(
                webView,
                BRIDGE_NAME,
                INSTAGRAM_ORIGINS,
                object : WebViewCompat.WebMessageListener {
                    override fun onPostMessage(
                        view: WebView,
                        message: WebMessageCompat,
                        sourceOrigin: Uri,
                        isMainFrame: Boolean,
                        replyProxy: JavaScriptReplyProxy
                    ) {
                        if (isMainFrame && message.data == "ready") hideLoadingOverlay()
                    }
                }
            )
        }

        val script = assets.open(FILTER_ASSET).bufferedReader().use { it.readText() }
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            // Runs before Instagram's own scripts on every page, and keeps working across its
            // in-app navigations, which never reach onPageStarted.
            WebViewCompat.addDocumentStartJavaScript(webView, script, INSTAGRAM_ORIGINS)
        } else {
            fallbackFilterScript = script
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    webView.canGoBack() -> webView.goBack()
                    // Instagram navigates in-page, so there can be nothing to go back to while
                    // the user is deep in a profile or post. Go up to the feed rather than quit.
                    !isOnFeed(webView.url) -> webView.loadUrl(DEFAULT_URL)
                    // Leave like a browser does: the WebView stays alive, so returning is
                    // instant rather than a cold reload of the whole feed.
                    else -> moveTaskToBack(true)
                }
            }
        })
    }

    private fun isOnFeed(url: String?): Boolean {
        val path = url?.let { Uri.parse(it).path }
        return path.isNullOrEmpty() || path == "/"
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyContentWidth()
    }

    /**
     * Instagram's mobile site is laid out for phones. On a tablet its header and nav bar stretch
     * edge to edge while posts sit in a centred column, and the story viewer anchors left with a
     * dead strip beside it. A centred phone-width column keeps all of it aligned, and paints
     * fewer pixels per frame.
     */
    private fun applyContentWidth() {
        val params = webView.layoutParams as FrameLayout.LayoutParams
        params.width = if (resources.configuration.screenWidthDp > MAX_CONTENT_WIDTH_DP) {
            (MAX_CONTENT_WIDTH_DP * resources.displayMetrics.density).toInt()
        } else {
            ViewGroup.LayoutParams.MATCH_PARENT
        }
        params.gravity = Gravity.CENTER_HORIZONTAL
        webView.layoutParams = params
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        webView.pauseTimers()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        webView.resumeTimers()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadComplete) }
        if (::webView.isInitialized) {
            webView.removeCallbacks(loadTimeout)
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        // Home, not the "Following" view: only home carries the stories tray. Suggested posts
        // and carousels on it are removed by the page filter (assets/cleangram.js).
        const val DEFAULT_URL = "https://www.instagram.com/"

        fun isInstagramHost(host: String?): Boolean {
            if (host == null) return false
            return host == "instagram.com" ||
                host.endsWith(".instagram.com") ||
                host == "cdninstagram.com" ||
                host.endsWith(".cdninstagram.com")
        }

        fun isReelsUrl(url: String): Boolean {
            val lower = url.lowercase(Locale.ROOT)
            return lower.contains("/reels/") ||
                lower.endsWith("/reels") ||
                lower.contains("/reel/")
        }

        private const val FILTER_ASSET = "cleangram.js"
        private const val BRIDGE_NAME = "CleanGramBridge"
        private const val MAX_CONTENT_WIDTH_DP = 600
        private val INSTAGRAM_ORIGINS = setOf("https://www.instagram.com", "https://instagram.com")

        private const val LOAD_TIMEOUT_MS = 25_000L
        private const val MIN_LOADING_MS = 600L
    }
}
