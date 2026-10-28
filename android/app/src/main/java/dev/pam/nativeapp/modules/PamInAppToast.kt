package dev.pam.nativeapp.modules

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
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
    val borderColor: Int? = null,
    val borderWidthDp: Float = 0f,
    val cornerRadiusDp: Float = 6f,
    val accentWidthDp: Float = 5f,
    val elevationDp: Float = 2f,
    val paddingHorizontalDp: Float = 15f,
    val paddingVerticalDp: Float = 10f,
    val minHeightDp: Float = 60f,
    val titleLines: Int = 1,
    val fitContent: Boolean = false,
    /** Packaged `asset://….png` drawn before the text. */
    val iconSource: String? = null,
    /** Icon-font glyph (UTF-8) drawn with [iconFontFamily] when there is no [iconSource]. */
    val iconGlyph: String? = null,
    val iconFontFamily: String? = null,
    val iconColor: Int? = null,
    val iconSizeDp: Float = 18f,
)

/**
 * In-app toast with title and message, matching react-native-toast-message's
 * default card: 60 dp min height, 6 dp radius, 5 dp accent bar, 15 dp side
 * padding, bold title over a muted message, slid in from the top (or bottom)
 * below the safe area (and above the keyboard), dismissed on tap or after the
 * visibility time. Border, radius, strip, elevation, paddings, title lines,
 * content-hugging width and a leading icon (packaged PNG or icon-font glyph)
 * follow the spec, so an app's own toast host can be reproduced.
 */
internal object PamInAppToast {
    private const val TAG = "pam-in-app-toast"
    private const val ANIMATION_MS = 250L

    fun show(activity: Activity, spec: PamInAppToastSpec, resolveTypeface: (String?, Int) -> Typeface) {
        val root = activity.window?.decorView as? ViewGroup ?: return
        root.findViewWithTag<View>(TAG)?.let { previous -> root.removeView(previous) }
        val density = activity.resources.displayMetrics.density
        fun dp(value: Float) = (value * density).roundToInt()
        val rootInsets = ViewCompat.getRootWindowInsets(root)
        val insets = rootInsets
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        // A bottom toast rests above the open keyboard, like a keyboard-aware
        // React Native toast host.
        val imeBottom = rootInsets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(spec.minHeightDp)
            setPadding(
                dp(spec.paddingHorizontalDp + spec.accentWidthDp),
                dp(spec.paddingVerticalDp),
                dp(spec.paddingHorizontalDp),
                dp(spec.paddingVerticalDp),
            )
            background = cardBackground(spec, density)
            elevation = dp(spec.elevationDp).toFloat()
            contentDescription = listOf(spec.title, spec.message).filter(String::isNotEmpty).joinToString(", ")
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        iconView(activity, spec, density, resolveTypeface)?.let { icon ->
            card.addView(
                icon,
                LinearLayout.LayoutParams(
                    icon.layoutParams?.width ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                    icon.layoutParams?.height ?: ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(10f) },
            )
        }
        val texts = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
        }
        card.addView(
            texts,
            if (spec.fitContent) {
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            } else {
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            },
        )
        if (spec.title.isNotEmpty()) {
            texts.addView(
                TextView(activity).apply {
                    text = spec.title
                    setTextColor(spec.titleColor)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, spec.titleSize)
                    typeface = resolveTypeface(spec.fontFamily, 700)
                    maxLines = spec.titleLines.coerceIn(1, 4)
                    ellipsize = android.text.TextUtils.TruncateAt.END
                },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = if (spec.message.isNotEmpty()) dp(2f) else 0 },
            )
        }
        if (spec.message.isNotEmpty()) {
            texts.addView(
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
        val margin = dp(spec.offsetDp) + if (spec.bottom) maxOf(insets?.bottom ?: 0, imeBottom) else insets?.top ?: 0
        val width = (root.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels)
        val container = FrameLayout(activity).apply {
            tag = TAG
            clipChildren = false
            clipToPadding = false
            // A content-hugging card is measured AT_MOST the width between
            // these 16 dp gutters (and the side insets).
            setPadding(dp(16f) + (insets?.left ?: 0), 0, dp(16f) + (insets?.right ?: 0), 0)
            val cardWidth = if (spec.fitContent) {
                ViewGroup.LayoutParams.WRAP_CONTENT
            } else {
                minOf(dp(340f), width - dp(32f))
            }
            addView(
                card,
                FrameLayout.LayoutParams(cardWidth, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
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

    /**
     * Accent strip (left, [PamInAppToastSpec.accentWidthDp], 0 = none) under
     * the card fill, with an optional border stroked over the whole card.
     */
    private fun cardBackground(spec: PamInAppToastSpec, density: Float): LayerDrawable {
        val radius = spec.cornerRadiusDp * density
        val accent = (spec.accentWidthDp * density).roundToInt()
        val layers = mutableListOf<GradientDrawable>()
        if (accent > 0) {
            layers += GradientDrawable().apply {
                setColor(spec.accentColor)
                cornerRadius = radius
            }
        }
        layers += GradientDrawable().apply {
            setColor(spec.backgroundColor)
            cornerRadius = radius
        }
        val border = spec.borderColor
        if (border != null && spec.borderWidthDp > 0f) {
            layers += GradientDrawable().apply {
                setColor(0)
                cornerRadius = radius
                setStroke((spec.borderWidthDp * density).roundToInt().coerceAtLeast(1), border)
            }
        }
        return LayerDrawable(layers.toTypedArray()).apply {
            if (accent > 0) setLayerInset(1, accent, 0, 0, 0)
        }
    }

    private fun iconView(
        activity: Activity,
        spec: PamInAppToastSpec,
        density: Float,
        resolveTypeface: (String?, Int) -> Typeface,
    ): View? {
        val size = (spec.iconSizeDp * density).roundToInt().coerceAtLeast(1)
        val tint = spec.iconColor ?: spec.titleColor
        spec.iconSource?.let { source ->
            val bitmap = runCatching {
                val path = dev.pam.nativeapp.render.normalizedPamAssetPath(source) ?: return@runCatching null
                activity.assets.open(path).use { stream ->
                    // Icons are tiny packaged PNGs; never decode a large asset here.
                    val bytes = stream.readNBytesCompat(MAX_ICON_BYTES + 1)
                    if (bytes.size > MAX_ICON_BYTES) null else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }.getOrNull() ?: return null
            return ImageView(activity).apply {
                setImageBitmap(bitmap)
                scaleType = ImageView.ScaleType.FIT_CENTER
                if (spec.iconColor != null) setColorFilter(tint)
                layoutParams = ViewGroup.LayoutParams(size, size)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }
        }
        val glyph = spec.iconGlyph?.takeIf(String::isNotEmpty) ?: return null
        return TextView(activity).apply {
            text = glyph
            setTextColor(tint)
            // Icon fonts never scale with the system font size (RN Icon).
            setTextSize(TypedValue.COMPLEX_UNIT_DIP, spec.iconSizeDp)
            typeface = resolveTypeface(spec.iconFontFamily, 400)
            includeFontPadding = false
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
        val output = java.io.ByteArrayOutputStream(minOf(limit, 4_096))
        val buffer = ByteArray(4_096)
        while (output.size() < limit) {
            val read = read(buffer, 0, minOf(buffer.size, limit - output.size()))
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private const val MAX_ICON_BYTES = 16 * 1024
}
