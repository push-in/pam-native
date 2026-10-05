package dev.pam.nativeapp.modules

import android.app.Activity
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/** Visual contract of one in-app toast (react-native-toast-message BaseToast). */
internal data class PamInAppToastSpec(
    val title: String,
    val message: String,
    val accentColor: Int,
    val backgroundColor: Int,
    val titleColor: Int,
    val messageColor: Int,
    val bottom: Boolean,
    val offsetDp: Float,
    val durationMs: Long,
    val titleSize: Float,
    val messageSize: Float,
    val fontFamily: String?,
)

/**
 * In-app toast with title and message, matching react-native-toast-message's
 * default card: 60 dp min height, 6 dp radius, 5 dp accent bar, 15 dp side
 * padding, bold title over a muted message, slid in from the top (or bottom)
 * below the safe area, dismissed on tap or after the visibility time.
 */
internal object PamInAppToast {
    private const val TAG = "pam-in-app-toast"
    private const val ANIMATION_MS = 250L

    fun show(activity: Activity, spec: PamInAppToastSpec, resolveTypeface: (String?, Int) -> Typeface) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        root.findViewWithTag<View>(TAG)?.let { previous -> root.removeView(previous) }
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).roundToInt()
        val insets = ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(60f)
            setPadding(dp(15f + 5f), dp(10f), dp(15f), dp(10f))
            background = LayerDrawable(
                arrayOf(
                    GradientDrawable().apply {
                        setColor(spec.accentColor)
                        cornerRadius = dp(6f).toFloat()
                    },
                    GradientDrawable().apply {
                        setColor(spec.backgroundColor)
                        cornerRadius = dp(6f).toFloat()
                    },
                ),
            ).apply { setLayerInset(1, dp(5f), 0, 0, 0) }
            elevation = dp(2f).toFloat()
            contentDescription = listOf(spec.title, spec.message).filter(String::isNotEmpty).joinToString(", ")
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        if (spec.title.isNotEmpty()) {
            card.addView(
                TextView(activity).apply {
                    text = spec.title
                    setTextColor(spec.titleColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.titleSize)
                    typeface = resolveTypeface(spec.fontFamily, 700)
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = if (spec.message.isNotEmpty()) dp(2f) else 0 },
            )
        }
        if (spec.message.isNotEmpty()) {
            card.addView(
                TextView(activity).apply {
                    text = spec.message
                    setTextColor(spec.messageColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.messageSize)
                    typeface = resolveTypeface(spec.fontFamily, 400)
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
            )
        }
        val margin = dp(spec.offsetDp) + if (spec.bottom) insets?.bottom ?: 0 else insets?.top ?: 0
        val width = (root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels)
        val container = FrameLayout(activity).apply {
            tag = TAG
            clipChildren = false
            addView(
                card,
                FrameLayout.LayoutParams(minOf(dp(340f), width - dp(32f)), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    gravity = Gravity.CENTER_HORIZONTAL or if (spec.bottom) Gravity.BOTTOM else Gravity.TOP
                    if (spec.bottom) bottomMargin = margin else topMargin = margin
                },
            )
        }
        root.addView(container, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        container.isClickable = false
        val hidden = { card.height.toFloat() + margin + dp(16f) }
        card.alpha = 0f
        card.post {
            card.translationY = if (spec.bottom) hidden() else -hidden()
            card.animate().translationY(0f).alpha(1f).setDuration(ANIMATION_MS).start()
        }
        val dismiss = Runnable {
            if (container.parent == null) return@Runnable
            card.animate()
                .translationY(if (spec.bottom) hidden() else -hidden())
                .alpha(0f)
                .setDuration(ANIMATION_MS)
                .withEndAction { (container.parent as? ViewGroup)?.removeView(container) }
                .start()
        }
        card.setOnClickListener {
            card.removeCallbacks(dismiss)
            dismiss.run()
        }
        card.postDelayed(dismiss, spec.durationMs.coerceIn(500L, 60_000L))
    }
}
