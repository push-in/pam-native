package dev.pam.nativeapp

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback

/**
 * 32-bit Android (TV sticks and boxes with `armeabi-v7a` only): the PHP SDK
 * needs 64-bit integers, so the app runs its web build (`pushinbr/pam-native-web`,
 * the same one Samsung and LG TVs run) from the APK assets in a WebView. The
 * remote's arrows, Enter and media keys reach the page as on a TV browser;
 * Back is handed to the app's focus engine and leaves the app at the root.
 */
class PamWebActivity : ComponentActivity() {
    private lateinit var web: WebView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        web = WebView(this).apply {
            setBackgroundColor(Color.BLACK)
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            addJavascriptInterface(Bridge(), "PamAndroid")
            isFocusable = true
            isFocusableInTouchMode = true
        }
        setContentView(web)
        web.loadUrl(ENTRY)
        web.requestFocus()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("window.PAM && PAM.key('GoBack')", null)
            }
        })
    }

    override fun onPause() {
        web.onPause()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        web.onResume()
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private inner class Bridge {
        @JavascriptInterface
        fun exit() {
            runOnUiThread { finish() }
        }
    }

    companion object {
        private const val ENTRY = "file:///android_asset/pam-web/index.html"

        /** True on devices that cannot run the PHP runtime, when the APK has the web build. */
        fun required(context: Context): Boolean =
            Build.SUPPORTED_64_BIT_ABIS.isEmpty() &&
                runCatching { context.assets.open("pam-web/index.html").close() }.isSuccess
    }
}
