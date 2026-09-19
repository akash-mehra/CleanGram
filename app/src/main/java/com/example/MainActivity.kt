package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.util.Locale
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var errorContainer: View
    private lateinit var retryButton: Button
    private lateinit var updateBar: View
    private lateinit var updateButton: Button

    private var pendingUpdate: Update? = null
    private var updateDownloadId: Long = -1L

    /** DownloadManager announces completion by broadcast; hand the file to the installer. */
    private val downloadComplete = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val id = intent?.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L) ?: -1L
            if (id != -1L && id == updateDownloadId) {
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
        retryButton = findViewById(R.id.retryButton)
        updateBar = findViewById(R.id.updateBar)
        updateButton = findViewById(R.id.updateButton)

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
            webView.reload()
        }

        if (savedInstanceState != null) {
            webView.restoreState(savedInstanceState)
        } else {
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
            setSupportZoom(true)
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
                    view?.loadUrl(DEFAULT_URL)
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
                injectDistractionFreeScript(view)
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectDistractionFreeScript(view)
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

    private fun showError() {
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

    private fun injectDistractionFreeScript(view: WebView?) {
        // Nothing to hide until the user is signed in, and the observer re-scans the DOM on
        // every mutation - which on the login screen means on every keystroke. Skip it there.
        val signedIn = CookieManager.getInstance().getCookie(DEFAULT_URL)
            .orEmpty().contains("sessionid")
        if (!signedIn) return
        view?.evaluateJavascript(DISTRACTION_FREE_INJECTION_JS, null)
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        webView.saveState(outState)
    }

    override fun onPause() {
        super.onPause()
        webView.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onResume() {
        super.onResume()
        webView.onResume()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(downloadComplete) }
        if (::webView.isInitialized) {
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        // Instagram's own chronological Following feed: only accounts you follow, no
        // algorithmic recommendations. Far more reliable than hiding suggestions in the DOM.
        const val DEFAULT_URL = "https://www.instagram.com/?variant=following"

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

        /**
         * Robust, reusable distraction-free injection script.
         * Injects custom CSS rules and uses a lightweight MutationObserver
         * to hide Reels, Explore shortcuts, and algorithmic feed suggestions.
         */
        val DISTRACTION_FREE_INJECTION_JS = """
            (function() {
                const STYLE_ID = 'cleangram-distraction-free-css';
                const SCRIPT_INIT_KEY = '__cleangram_observer_active__';

                const customCss = `
                    /* 1. Hide Reels navigation tabs, shortcuts, and floating action buttons */
                    a[href*="/reels/"],
                    a[href*="/reels"],
                    a[href*="/reel/"],
                    svg[aria-label*="Reels" i],
                    svg[aria-label*="reels" i],
                    [aria-label*="Reels" i],
                    [data-testid*="reels" i],

                    /* 2. Hide Explore / Search discovery feeds */
                    a[href*="/explore/"],
                    a[href*="/explore"],
                    svg[aria-label*="Explore" i],
                    svg[aria-label*="explore" i],
                    [aria-label*="Explore" i],
                    [data-testid*="explore" i],

                    /* 3. Hide algorithmic suggestion elements and containers in feed */
                    [data-testid*="suggested" i],
                    div[data-testid*="suggestion" i] {
                        display: none !important;
                        visibility: hidden !important;
                        pointer-events: none !important;
                        height: 0 !important;
                        max-height: 0 !important;
                        opacity: 0 !important;
                        overflow: hidden !important;
                    }
                `;

                function injectCSS() {
                    if (!document.getElementById(STYLE_ID)) {
                        const style = document.createElement('style');
                        style.id = STYLE_ID;
                        style.type = 'text/css';
                        style.appendChild(document.createTextNode(customCss));
                        const target = document.head || document.documentElement;
                        if (target) {
                            target.appendChild(style);
                        }
                    }
                }

                function removeDistractionNodes() {
                    // Hide parent containers for Reels & Explore navigation items
                    const targetSelectors = [
                        'a[href*="/reels/"]',
                        'a[href*="/reels"]',
                        'a[href*="/reel/"]',
                        'a[href*="/explore/"]',
                        'a[href*="/explore"]',
                        'svg[aria-label*="Reels" i]',
                        'svg[aria-label*="Explore" i]'
                    ];

                    document.querySelectorAll(targetSelectors.join(',')).forEach(function(el) {
                        const navParent = el.closest('li, [role="tab"], [role="menuitem"], div[class*="nav"], div[class*="Nav"]');
                        if (navParent && navParent !== document.body && navParent !== document.documentElement) {
                            navParent.style.setProperty('display', 'none', 'important');
                            navParent.style.setProperty('visibility', 'hidden', 'important');
                        } else {
                            el.style.setProperty('display', 'none', 'important');
                            el.style.setProperty('visibility', 'hidden', 'important');
                        }
                    });

                    // Hide "Suggested for you" algorithmic recommendation containers in main feed
                    const suggestionKeywords = [
                        'suggested for you',
                        'suggestions for you',
                        'suggested posts',
                        'suggested accounts',
                        'suggested reels'
                    ];

                    const textNodes = document.querySelectorAll('span, p, h2, h3, h4, div');
                    textNodes.forEach(function(node) {
                        if (node.children.length === 0 && node.textContent) {
                            const trimmedText = node.textContent.trim().toLowerCase();
                            if (suggestionKeywords.some(function(keyword) { return trimmedText.includes(keyword); })) {
                                const feedContainer = node.closest('article, section, div[data-testid], div[role="presentation"]');
                                if (feedContainer && feedContainer !== document.body && feedContainer !== document.documentElement) {
                                    feedContainer.style.setProperty('display', 'none', 'important');
                                    feedContainer.style.setProperty('visibility', 'hidden', 'important');
                                } else if (node.parentElement) {
                                    node.parentElement.style.setProperty('display', 'none', 'important');
                                }
                            }
                        }
                    });
                }

                // Initial injection
                injectCSS();
                removeDistractionNodes();

                // Setup MutationObserver to continuously hide dynamically rendered items as user scrolls
                if (!window[SCRIPT_INIT_KEY]) {
                    window[SCRIPT_INIT_KEY] = true;
                    const observer = new MutationObserver(function(mutations) {
                        injectCSS();
                        removeDistractionNodes();
                    });

                    const target = document.documentElement || document.body;
                    if (target) {
                        observer.observe(target, {
                            childList: true,
                            subtree: true
                        });
                    }
                }
            })();
        """.trimIndent()
    }
}
