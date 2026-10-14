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

    private lateinit var soundButton: Button
    private lateinit var fullscreenContainer: FrameLayout
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

        val rootContainer = findViewById<View>(R.id.rootContainer)
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        errorContainer = findViewById(R.id.errorContainer)
        retryButton = findViewById(R.id.retryButton)
        soundButton = findViewById(R.id.soundButton)
        fullscreenContainer = findViewById(R.id.fullscreenContainer)
        muted = getPreferences(MODE_PRIVATE).getBoolean("muted", true)
        updateSoundButton()
        soundButton.setOnClickListener {
            muted = !muted
            getPreferences(MODE_PRIVATE).edit().putBoolean("muted", muted).apply()
            updateSoundButton()
            applySoundPreference()
        }

        // Handle edge-to-edge window insets cleanly
        ViewCompat.setOnApplyWindowInsetsListener(rootContainer) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val bottomInsets = insets.getInsets(WindowInsetsCompat.Type.navigationBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(0, statusBars.top, 0, bottomInsets.bottom)
            insets
        }

        setupWebView()
        setupBackNavigation()

        retryButton.setOnClickListener {
            errorContainer.visibility = View.GONE
            webView.visibility = View.VISIBLE
            webView.reload()
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

        // 3. WebChromeClient for file uploads, photo pickers, alerts, and progress
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                if (view == null || customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
                fullscreenContainer.addView(view, FrameLayout.LayoutParams(-1, -1))
                fullscreenContainer.visibility = View.VISIBLE
                webView.visibility = View.GONE
                soundButton.bringToFront()
                WindowInsetsControllerCompat(window, fullscreenContainer).apply {
                    systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    hide(WindowInsetsCompat.Type.systemBars())
                }
            }

            override fun onHideCustomView() = exitFullscreen()

            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                if (newProgress < 100) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                } else {
                    progressBar.visibility = View.GONE
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
                errorContainer.visibility = View.GONE
                webView.visibility = View.VISIBLE
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
                if (request?.isForMainFrame == true) {
                    webView.visibility = View.GONE
                    errorContainer.visibility = View.VISIBLE
                }
            }
        }
    }

    private fun injectDistractionFreeScript(view: WebView?) {
        val host = view?.url?.let { Uri.parse(it).host }?.lowercase(Locale.ROOT)
        if (!isInstagramHost(host)) return
        view?.evaluateJavascript(DISTRACTION_FREE_INJECTION_JS, null)
        applySoundPreference()
    }

    private fun updateSoundButton() {
        soundButton.setText(if (muted) R.string.sound_off else R.string.sound_on)
        soundButton.contentDescription = getString(if (muted) R.string.unmute_video else R.string.mute_video)
    }

    private fun applySoundPreference() {
        webView.evaluateJavascript("""
            (function() {
                window.__cleangramMuted = $muted;
                function applySound() {
                    document.querySelectorAll('video, audio').forEach(function(media) {
                        media.muted = window.__cleangramMuted;
                    });
                }
                applySound();
                if (!window.__cleangramSoundInstalled) {
                    window.__cleangramSoundInstalled = true;
                    document.addEventListener('play', applySound, true);
                    document.addEventListener('volumechange', function(event) {
                        const media = event.target;
                        if (media.matches && media.matches('video, audio') && media.muted !== window.__cleangramMuted) {
                            media.muted = window.__cleangramMuted;
                        }
                    }, true);
                    new MutationObserver(applySound).observe(document.documentElement, {childList: true, subtree: true});
                }
            })();
        """.trimIndent(), null)
    }

    private fun exitFullscreen() {
        if (customView == null) return
        fullscreenContainer.removeAllViews()
        fullscreenContainer.visibility = View.GONE
        customView = null
        webView.visibility = View.VISIBLE
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
        if (::webView.isInitialized) {
            exitFullscreen()
            webView.stopLoading()
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val DEFAULT_URL = "https://www.instagram.com/"

        fun isInstagramHost(host: String?): Boolean {
            if (host == null) return false
            return host == "instagram.com" ||
                host.endsWith(".instagram.com") ||
                host == "cdninstagram.com" ||
                host.endsWith(".cdninstagram.com")
        }

        fun isReelsUrl(url: String): Boolean {
            val uri = Uri.parse(url)
            if (!isInstagramHost(uri.host?.lowercase(Locale.ROOT))) return false
            val path = uri.path?.lowercase(Locale.ROOT)?.trimEnd('/')
            return path == "/reels" || path?.startsWith("/reels/") == true
        }

        fun isDistractionUrl(url: String): Boolean {
            val uri = Uri.parse(url)
            if (!isInstagramHost(uri.host?.lowercase(Locale.ROOT))) return false
            val path = uri.path?.lowercase(Locale.ROOT)?.trimEnd('/')
            return isReelsUrl(url) || path == "/explore" || path?.startsWith("/explore/") == true
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
                    html[data-cleangram-home] [data-testid*="suggested" i],
                    html[data-cleangram-home] div[data-testid*="suggestion" i] {
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
                    if (document.documentElement) {
                        document.documentElement.toggleAttribute('data-cleangram-home', location.pathname === '/');
                    }
                    enforceRoute();
                    // Hide parent containers for Reels & Explore navigation items
                    const targetSelectors = [
                        'a[href*="/reels/"]',
                        'a[href*="/reels"]',
                        'a[href*="/explore/"]',
                        'a[href*="/explore"]',
                        'svg[aria-label*="Reels" i]',
                        'svg[aria-label*="Explore" i]'
                    ];

                    document.querySelectorAll(targetSelectors.join(',')).forEach(function(el) {
                        const navParent = el.closest('a, button, [role="link"], [role="button"], [role="tab"], [role="menuitem"]');
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

                    if (location.pathname !== '/') return;
                    // Reels embedded in the home feed are distractions too; DM
                    // attachments and an explicitly opened shared post stay intact.
                    document.querySelectorAll('a[href*="/reel/"]').forEach(function(link) {
                        const post = link.closest('article');
                        if (post) post.style.setProperty('display', 'none', 'important');
                    });
                    const textNodes = document.querySelectorAll('span, p, h2, h3, h4, div');
                    textNodes.forEach(function(node) {
                        if (node.children.length === 0 && node.textContent) {
                            const trimmedText = node.textContent.trim().toLowerCase();
                            if (suggestionKeywords.some(function(keyword) { return trimmedText.includes(keyword); })) {
                                const feedContainer = node.closest('article, section, div[data-testid], div[role="presentation"]');
                                if (feedContainer && feedContainer !== document.body && feedContainer !== document.documentElement) {
                                    feedContainer.style.setProperty('display', 'none', 'important');
                                    feedContainer.style.setProperty('visibility', 'hidden', 'important');
                                } else if (node.parentElement && node.parentElement !== document.body) {
                                    node.parentElement.style.setProperty('display', 'none', 'important');
                                }
                            }
                        }
                    });
                }


                function blockedRoute(url) {
                    try {
                        const parsed = new URL(url, location.href);
                        if (parsed.origin !== location.origin) return false;
                        return /^\/(reels|explore)(\/|$)/i.test(parsed.pathname);
                    } catch (_) { return false; }
                }

                function enforceRoute() {
                    if (blockedRoute(location.href)) location.replace('/');
                }

                // Instagram changes routes without loading a new document.
                if (!window.__cleangramRouteGuard) {
                    window.__cleangramRouteGuard = true;
                    ['pushState', 'replaceState'].forEach(function(method) {
                        const original = history[method];
                        history[method] = function(state, title, url) {
                            if (url != null && blockedRoute(url)) {
                                enforceRoute();
                                return;
                            }
                            const result = original.apply(this, arguments);
                            removeDistractionNodes();
                            return result;
                        };
                    });
                    document.addEventListener('click', function(event) {
                        const link = event.target.closest && event.target.closest('a[href]');
                        if (link && blockedRoute(link.href)) {
                            event.preventDefault();
                            event.stopImmediatePropagation();
                        }
                    }, true);
                    window.addEventListener('popstate', removeDistractionNodes);
                    window.addEventListener('hashchange', removeDistractionNodes);
                }

                // Initial injection
                injectCSS();
                removeDistractionNodes();

                // Setup MutationObserver to continuously hide dynamically rendered items as user scrolls
                if (!window[SCRIPT_INIT_KEY]) {
                    window[SCRIPT_INIT_KEY] = true;
                    let pending = false;
                    const observer = new MutationObserver(function() {
                        if (pending) return;
                        pending = true;
                        requestAnimationFrame(function() {
                            pending = false;
                            injectCSS();
                            removeDistractionNodes();
                        });
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
