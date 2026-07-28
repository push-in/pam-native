package dev.pam.nativeapp.render

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Handler
import android.os.Looper

@SuppressLint("SetJavaScriptEnabled")
internal class PamWebView(context: Context) : WebView(context) {
    private val defaultUserAgent = settings.userAgentString
    private var source = ""
    private var injectedJavaScript = ""
    private var allowedHosts: Set<String> = emptySet()
    private val main = Handler(Looper.getMainLooper())
    private var loadTimeout: Runnable? = null
    var onLoad: (() -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onMessage: ((String) -> Unit)? = null

    init {
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                handlePamMessage(request.url) || request.isForMainFrame && !allows(request.url)

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
                Uri.parse(url).let { handlePamMessage(it) || !allows(it) }

            override fun onPageFinished(view: WebView, url: String) {
                cancelLoadTimeout()
                if (settings.javaScriptEnabled) {
                    evaluateJavascript(
                        """
                        window.PamNative = window.PamNative || {};
                        window.PamNative.postMessage = function(value) {
                          window.location.href = 'pam://message?data=' +
                            encodeURIComponent(String(value));
                        };
                        """.trimIndent(),
                        null,
                    )
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

    fun setSource(value: String) {
        if (source == value) return
        source = value
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
        injectedJavaScript = value
    }

    fun setAllowsInlineMedia(value: Boolean) {
        settings.mediaPlaybackRequiresUserGesture = !value
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

    private fun handlePamMessage(uri: Uri): Boolean {
        if (uri.scheme != "pam" || uri.host != "message") return false
        onMessage?.invoke(uri.getQueryParameter("data").orEmpty())
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
