package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

/**
 * Page ↔ host channel rules shared by the Android and iOS web views, kept
 * pure for JVM tests.
 *
 * Pages post with `window.PamNative.postMessage(text)` or, for pages written
 * for react-native-webview, `window.ReactNativeWebView.postMessage(text)`;
 * the second exists from document start. The host answers by changing
 * `injectedJavaScript`: a loaded page runs each new script once. Messages
 * carry the main-frame URL; the host still checks its own protocol (nonce),
 * since any frame of the page can reach the interface.
 */
internal object PamWebBridge {
    const val INTERFACE_NAME = "ReactNativeWebView"
    const val MAX_MESSAGE_CHARS = 1_048_576

    /** `PamNative.postMessage` as an alias of the document-start interface. */
    const val ALIAS_SCRIPT =
        "window.PamNative=window.PamNative||{};" +
            "window.PamNative.postMessage=function(v){window.ReactNativeWebView.postMessage(String(v));};true;"

    fun accepts(message: String?): Boolean =
        message != null && message.length <= MAX_MESSAGE_CHARS

    /** A changed, non-empty script runs at once on a page that already finished loading. */
    fun runsOnChange(previous: String, next: String, pageLoaded: Boolean): Boolean =
        pageLoaded && next.isNotEmpty() && next != previous

    /** `ScrollOverScrollMode` (1 auto, 2 always, 3 never); a web view that does not scroll never over-scrolls. */
    fun overScrollMode(mode: Int, scrollEnabled: Boolean): Int = when {
        !scrollEnabled || mode == 3 -> View.OVER_SCROLL_NEVER
        mode == 2 -> View.OVER_SCROLL_ALWAYS
        else -> View.OVER_SCROLL_IF_CONTENT_SCROLLS
    }

    /** Paused while the host app is in the background or the view is not shown (covered route, hidden tab). */
    fun runs(hostActive: Boolean, shown: Boolean): Boolean = hostActive && shown
}

@SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
internal class PamWebView(context: Context) : WebView(context) {
    private val defaultUserAgent = settings.userAgentString
    private var source = ""
    private var injectedJavaScript = ""
    private var allowedHosts: Set<String> = emptySet()
    private val main = Handler(Looper.getMainLooper())
    private var loadTimeout: Runnable? = null
    private var pageLoaded = false
    private var scrollEnabled = true
    private var overScrollModeValue = 1
    private var hostActive = true
    private var running = true
    var onLoad: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /** Message text and the URL of the page that posted it. */
    var onMessage: ((String, String) -> Unit)? = null

    init {
        // Hardened defaults: no file/content access, no popups or new
        // windows, no mixed content, no geolocation, no third-party cookies
        // and no page zoom. Navigation stays inside `allowedHosts`.
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.javaScriptCanOpenWindowsAutomatically = false
        settings.setSupportMultipleWindows(false)
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.setGeolocationEnabled(false)
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        addJavascriptInterface(MessageInterface(), PamWebBridge.INTERFACE_NAME)
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handlePamMessage(request.url) || request.isForMainFrame && !allows(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                Uri.parse(url).let { handlePamMessage(it) || !allows(it) }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                pageLoaded = false
            }

            override fun onPageFinished(view: WebView, url: String) {
                cancelLoadTimeout()
                pageLoaded = true
                if (settings.javaScriptEnabled) {
                    evaluateJavascript(PamWebBridge.ALIAS_SCRIPT, null)
                }
                if (settings.javaScriptEnabled && injectedJavaScript.isNotEmpty()) {
                    evaluateJavascript(injectedJavaScript, null)
                }
                onLoad?.invoke()
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) {
                    cancelLoadTimeout()
                    onError?.invoke(error.description.toString())
                }
            }
        }
    }

    /** Called on a binder thread; delivered on the main thread with the page URL of that moment. */
    private inner class MessageInterface {
        @JavascriptInterface
        fun postMessage(value: String?) {
            if (!PamWebBridge.accepts(value)) return
            val text = value ?: return
            main.post { onMessage?.invoke(text, url.orEmpty()) }
        }
    }

    fun setSource(value: String) {
        if (source == value) return
        source = value
        pageLoaded = false
        if (value.isEmpty()) {
            cancelLoadTimeout()
            stopLoading()
            return
        }
        scheduleLoadTimeout()
        if (value.trimStart().startsWith("<")) {
            loadDataWithBaseURL(null, value, "text/html", "UTF-8", null)
        } else {
            loadUrl(value)
        }
    }

    fun setJavaScriptEnabled(value: Boolean) {
        settings.javaScriptEnabled = value
    }

    fun setDomStorageEnabled(value: Boolean) {
        settings.domStorageEnabled = value
    }

    fun setUserAgent(value: String) {
        settings.userAgentString = value.ifEmpty { defaultUserAgent }
    }

    fun setInjectedJavaScript(value: String) {
        val runNow = settings.javaScriptEnabled &&
            PamWebBridge.runsOnChange(injectedJavaScript, value, pageLoaded)
        injectedJavaScript = value
        if (runNow) evaluateJavascript(value, null)
    }

    fun setAllowsInlineMedia(value: Boolean) {
        settings.mediaPlaybackRequiresUserGesture = !value
    }

    fun setScrollEnabled(value: Boolean) {
        scrollEnabled = value
        isVerticalScrollBarEnabled = value
        isHorizontalScrollBarEnabled = value
        overScrollMode = PamWebBridge.overScrollMode(overScrollModeValue, scrollEnabled)
    }

    fun setOverScrollModeValue(value: Int) {
        overScrollModeValue = value
        overScrollMode = PamWebBridge.overScrollMode(overScrollModeValue, scrollEnabled)
    }

    fun setAllowedHosts(value: String) {
        allowedHosts = value.lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .map(String::lowercase)
            .toSet()
        val current = url?.let(Uri::parse)
        if (current != null && !allows(current)) {
            stopLoading()
            onError?.invoke("WebView navigation was blocked by the allowed-host policy")
        }
    }

    fun onHostPause() {
        hostActive = false
        syncRunning()
    }

    fun onHostResume() {
        hostActive = true
        syncRunning()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        syncRunning()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        syncRunning()
    }

    /** A covered route or a background app pauses the page (no frames, timers or animations of this view). */
    private fun syncRunning() {
        val next = PamWebBridge.runs(hostActive, isShown && windowVisibility == View.VISIBLE)
        if (next == running) return
        running = next
        if (next) onResume() else onPause()
    }

    private fun handlePamMessage(uri: Uri): Boolean {
        if (uri.scheme != "pam" || uri.host != "message") return false
        val text = uri.getQueryParameter("data").orEmpty()
        if (PamWebBridge.accepts(text)) onMessage?.invoke(text, url.orEmpty())
        return true
    }

    private fun allows(uri: Uri): Boolean {
        if (allowedHosts.isEmpty() || uri.scheme == "about" || uri.scheme == "data") return true
        val host = uri.host?.lowercase() ?: return false
        val allowed = host in allowedHosts
        if (!allowed) {
            cancelLoadTimeout()
            onError?.invoke("WebView blocked navigation to $host")
        }
        return allowed
    }

    private fun scheduleLoadTimeout() {
        cancelLoadTimeout()
        val timeout = Runnable {
            stopLoading()
            onError?.invoke("WebView navigation timed out after 30 seconds")
        }
        loadTimeout = timeout
        main.postDelayed(timeout, 30_000)
    }

    private fun cancelLoadTimeout() {
        loadTimeout?.let(main::removeCallbacks)
        loadTimeout = null
    }

    override fun onDetachedFromWindow() {
        cancelLoadTimeout()
        super.onDetachedFromWindow()
    }
}
