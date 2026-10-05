package dev.pam.nativeapp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.StyleSpan
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat

/**
 * Runtime error surface, modelled on React Native's LogBox/RedBox.
 *
 * Developer mode: non-fatal errors queue behind a compact bottom toast that
 * expands into a full-screen, scrollable inspector (message, app frame first,
 * source snippet, collapsible framework/vendor frames, Dismiss/Copy/Reload and
 * prev/next for queued errors). Fatal render/boot errors open the inspector
 * directly. A dismissed error is not shown again until the runtime reloads,
 * so an error raised on every frame cannot trap the user in a re-show loop.
 *
 * Production mode (release builds, or `devErrorOverlay: false`): stacks are
 * never shown. Non-fatal errors are only logged (PHP App::onError() listeners
 * already received them) and fatal errors show a friendly retry screen.
 *
 * The overlay itself never intercepts touches: only its visible toast, panel
 * or fallback children are clickable, and they are GONE when not shown.
 */
internal class ErrorOverlay @JvmOverloads constructor(
    context: Context,
    val developerMode: Boolean = false,
    private val safeArea: () -> Insets = { Insets.NONE },
    private val dark: () -> Boolean = { false },
    private val onReload: () -> Unit = {},
    private val onExit: () -> Unit = {},
) : FrameLayout(context) {
    enum class State { HIDDEN, TOAST, PANEL, FALLBACK }

    private class Entry(val report: RuntimeErrorReport, var count: Int = 1)

    private val entries = ArrayList<Entry>()
    private val dismissed = HashSet<String>()
    private val expandedGroups = HashSet<Int>()
    private var index = 0
    private var palette = Palette.of(false)

    var state: State = State.HIDDEN
        private set

    /** Errors waiting in the developer overlay. */
    val entryCount: Int get() = entries.size

    /** Zero-based position of the error shown in the inspector. */
    val currentIndex: Int get() = index

    /** Occurrences of a dismissed error suppressed until the next reload. */
    var suppressedCount: Int = 0
        private set

    internal val currentReport: RuntimeErrorReport? get() = entries.getOrNull(index)?.report

    // Toast
    private val toastBadge = text(12f, bold = true).apply { gravity = Gravity.CENTER }
    private val toastTitle = text(13f, bold = true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val toastMessage = text(14f).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
    private val toastClose = button(context.getString(R.string.pam_error_close_symbol)).apply {
        contentDescription = context.getString(R.string.pam_error_dismiss_all_description)
        textSize = 20f
        setOnClickListener { dismissAll() }
    }
    internal val toast: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isClickable = true
        isFocusable = true
        contentDescription = context.getString(R.string.pam_error_open_description)
        setOnClickListener { expand() }
        setPadding(dp(14), dp(10), dp(4), dp(10))
        elevation = dp(8).toFloat()
        addView(toastBadge, LinearLayout.LayoutParams(dp(28), dp(28)))
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(toastTitle)
                addView(toastMessage)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            },
        )
        addView(toastClose, LinearLayout.LayoutParams(dp(48), dp(48)))
        visibility = GONE
    }

    // Inspector panel
    private val headerTitle = text(15f, bold = true)
    private val headerCounter = text(14f, bold = true).apply { gravity = Gravity.CENTER }
    private val previous = button("‹").apply {
        textSize = 24f
        contentDescription = context.getString(R.string.pam_error_previous)
        setOnClickListener { select(index - 1) }
    }
    private val next = button("›").apply {
        textSize = 24f
        contentDescription = context.getString(R.string.pam_error_next)
        setOnClickListener { select(index + 1) }
    }
    private val minimize = button(context.getString(R.string.pam_error_minimize)).apply {
        textSize = 14f
        setOnClickListener { minimize() }
    }
    private val header = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(headerTitle, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(previous, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(headerCounter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
        addView(next, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(minimize, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
    }
    private val typeView = text(13f, mono = true).apply { setTextIsSelectable(true) }
    private val messageView = text(20f).apply {
        setTextIsSelectable(true)
        setLineSpacing(0f, 1.2f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private val locationView = text(13f, mono = true).apply { setTextIsSelectable(true) }
    private val sourceLabel = sectionLabel(context.getString(R.string.pam_error_source))
    private val sourceView = text(12.5f, mono = true).apply {
        setTextIsSelectable(true)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        setLineSpacing(0f, 1.15f)
    }
    private val stackLabel = sectionLabel(context.getString(R.string.pam_error_call_stack))
    private val stackView = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    private val body = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(typeView)
        addView(messageView, column(top = 6))
        addView(locationView, column(top = 12))
        addView(sourceLabel, column(top = 24))
        addView(sourceView, column(top = 8))
        addView(stackLabel, column(top = 24))
        addView(stackView, column(top = 4))
    }
    internal val scroll = ScrollView(context).apply {
        isFillViewport = true
        clipToPadding = false
        addView(body)
    }
    internal val dismissButton = footerButton(context.getString(R.string.pam_error_dismiss)).apply {
        contentDescription = context.getString(R.string.pam_error_dismiss_description)
        setOnClickListener { dismissCurrent() }
    }
    internal val copyButton = footerButton(context.getString(R.string.pam_error_copy)).apply {
        setOnClickListener { copyCurrent() }
    }
    internal val reloadButton = footerButton(context.getString(R.string.pam_error_reload)).apply {
        setOnClickListener { reload() }
    }
    private val footer = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(dismissButton, LinearLayout.LayoutParams(0, dp(48), 1f))
        addView(copyButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
        addView(reloadButton, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginStart = dp(8) })
    }
    internal val panel: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        isClickable = true
        isFocusable = true
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        addView(footer, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        visibility = GONE
    }

    // Production fallback
    private val fallbackIcon = text(28f, bold = true).apply { text = "!"; gravity = Gravity.CENTER }
    private val fallbackTitle = text(22f, bold = true).apply {
        text = context.getString(R.string.pam_error_fallback_title)
        gravity = Gravity.CENTER
    }
    private val fallbackMessage = text(16f).apply {
        text = context.getString(R.string.pam_error_fallback_message)
        gravity = Gravity.CENTER
        setLineSpacing(0f, 1.2f)
    }
    internal val fallbackRetry = footerButton(context.getString(R.string.pam_error_fallback_retry)).apply {
        setOnClickListener { reload() }
    }
    internal val fallback: LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(dp(32), dp(32), dp(32), dp(32))
        addView(fallbackIcon, LinearLayout.LayoutParams(dp(64), dp(64)))
        addView(fallbackTitle, column(top = 20))
        addView(fallbackMessage, column(top = 8))
        addView(fallbackRetry, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
            topMargin = dp(28)
        })
        visibility = GONE
    }

    init {
        elevation = dp(24).toFloat()
        // The overlay container never consumes touches itself.
        isClickable = false
        isFocusable = false
        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        addView(panel, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(fallback, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(
            toast,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            if (state != State.HIDDEN) applyInsets()
            insets
        }
        visibility = GONE
    }

    /** Legacy entry point: parses a host or PHP diagnostic and reports it. */
    fun showError(message: String) = report(RuntimeErrorReport.parse(message))

    fun report(report: RuntimeErrorReport) {
        if (!developerMode) {
            if (report.fatal) showFallback()
            return
        }
        if (report.fingerprint in dismissed) {
            suppressedCount++
            return
        }
        val existing = entries.indexOfFirst { it.report.fingerprint == report.fingerprint }
        if (existing >= 0) {
            entries[existing].count++
            if (report.fatal && state != State.PANEL) {
                index = existing
                show(State.PANEL)
            } else {
                render()
            }
            return
        }
        entries += Entry(report)
        while (entries.size > MAX_ENTRIES) {
            entries.removeAt(0)
            if (index > 0) index--
        }
        when {
            state == State.PANEL -> render()
            report.fatal -> {
                index = entries.lastIndex
                show(State.PANEL)
            }
            else -> {
                index = entries.lastIndex
                show(State.TOAST)
            }
        }
    }

    /** Friendly, stack-free screen for fatal errors in production. */
    fun showFallback() {
        entries.clear()
        show(State.FALLBACK)
    }

    fun expand() {
        if (entries.isNotEmpty()) show(State.PANEL)
    }

    fun minimize() {
        if (entries.isEmpty()) hide() else show(State.TOAST)
    }

    fun select(position: Int) {
        if (position !in entries.indices) return
        index = position
        expandedGroups.clear()
        render()
        scroll.scrollTo(0, 0)
    }

    /** Dismisses the error shown in the inspector; the next queued one follows. */
    fun dismissCurrent() {
        val entry = entries.getOrNull(index) ?: return hide()
        dismissed += entry.report.fingerprint
        entries.removeAt(index)
        if (entries.isEmpty()) {
            hide()
            return
        }
        index = index.coerceAtMost(entries.lastIndex)
        expandedGroups.clear()
        render()
        scroll.scrollTo(0, 0)
    }

    fun dismissAll() {
        entries.forEach { dismissed += it.report.fingerprint }
        hide()
    }

    /** Clears every error and suppression (runtime reload / hot reload). */
    fun clearError() {
        dismissed.clear()
        suppressedCount = 0
        hide()
    }

    /** A committed frame proves a production recovery succeeded. */
    fun onFrameCommitted() {
        if (state == State.FALLBACK) hide()
    }

    /** Back closes the inspector (dismissing queued errors) or leaves a fatal fallback. */
    fun consumeBack(): Boolean = when (state) {
        State.PANEL -> {
            dismissAll()
            true
        }
        State.FALLBACK -> {
            onExit()
            true
        }
        State.TOAST, State.HIDDEN -> false
    }

    /** True while the overlay covers the app and owns input. */
    val isBlocking: Boolean get() = state == State.PANEL || state == State.FALLBACK

    private fun reload() {
        clearError()
        onReload()
    }

    private fun copyCurrent() {
        val report = currentReport ?: return
        val clipboard = context.getSystemService(ClipboardManager::class.java) ?: return
        runCatching {
            clipboard.setPrimaryClip(ClipData.newPlainText(report.shortType, report.copyText()))
            Toast.makeText(context, R.string.pam_error_copied, Toast.LENGTH_SHORT).show()
        }.onFailure { Log.w(TAG, "Cannot copy runtime error", it) }
    }

    private fun hide() {
        entries.clear()
        expandedGroups.clear()
        index = 0
        state = State.HIDDEN
        toast.visibility = GONE
        panel.visibility = GONE
        fallback.visibility = GONE
        visibility = GONE
    }

    private fun show(target: State) {
        val wasPanel = state == State.PANEL
        state = target
        palette = Palette.of(dark())
        visibility = VISIBLE
        toast.visibility = if (target == State.TOAST) VISIBLE else GONE
        panel.visibility = if (target == State.PANEL) VISIBLE else GONE
        fallback.visibility = if (target == State.FALLBACK) VISIBLE else GONE
        applyInsets()
        render()
        when (target) {
            State.PANEL -> if (!wasPanel) {
                scroll.scrollTo(0, 0)
                panel.requestFocus()
                currentReport?.let { panel.announceForAccessibility("${it.phaseLabel}. ${it.shortType}. ${it.message}") }
            }
            State.FALLBACK -> fallback.announceForAccessibility(fallbackTitle.text)
            else -> Unit
        }
        bringToFront()
    }

    private fun applyInsets() {
        val insets = safeArea()
        header.setPadding(insets.left + dp(16), insets.top + dp(8), insets.right + dp(8), dp(8))
        body.setPadding(insets.left + dp(20), dp(20), insets.right + dp(20), dp(24))
        footer.setPadding(insets.left + dp(16), dp(12), insets.right + dp(16), insets.bottom + dp(12))
        (toast.layoutParams as LayoutParams).setMargins(
            insets.left + dp(12),
            0,
            insets.right + dp(12),
            insets.bottom + dp(12),
        )
        fallback.setPadding(insets.left + dp(32), insets.top + dp(32), insets.right + dp(32), insets.bottom + dp(32))
        toast.requestLayout()
    }

    private fun render() {
        val colors = palette
        when (state) {
            State.TOAST -> renderToast(colors)
            State.PANEL -> renderPanel(colors)
            State.FALLBACK -> renderFallback(colors)
            State.HIDDEN -> Unit
        }
    }

    private fun renderToast(colors: Palette) {
        val entry = entries.getOrNull(index) ?: return
        toast.background = rounded(colors.toast, dp(14).toFloat(), colors.border)
        toastBadge.text = if (entries.size > 1) entries.size.toString() else "!"
        toastBadge.setTextColor(Color.WHITE)
        toastBadge.background = rounded(colors.accent, dp(14).toFloat())
        toastTitle.setTextColor(colors.accentText)
        toastTitle.text = buildString {
            append(entry.report.shortType)
            if (entry.count > 1) append("  ×").append(entry.count)
        }
        toastMessage.setTextColor(colors.text)
        toastMessage.text = entry.report.message
        toastClose.setTextColor(colors.muted)
    }

    private fun renderPanel(colors: Palette) {
        val entry = entries.getOrNull(index) ?: return
        val report = entry.report
        panel.setBackgroundColor(colors.background)
        header.setBackgroundColor(colors.accent)
        headerTitle.setTextColor(Color.WHITE)
        headerTitle.text = report.phaseLabel
        val multiple = entries.size > 1
        headerCounter.text = context.getString(R.string.pam_error_counter, index + 1, entries.size)
        listOf(previous, headerCounter, next).forEach { it.visibility = if (multiple) VISIBLE else GONE }
        previous.isEnabled = index > 0
        next.isEnabled = index < entries.lastIndex
        previous.alpha = if (previous.isEnabled) 1f else 0.4f
        next.alpha = if (next.isEnabled) 1f else 0.4f
        listOf(previous, next, headerCounter, minimize).forEach { it.setTextColor(Color.WHITE) }
        minimize.visibility = if (report.fatal) GONE else VISIBLE

        typeView.setTextColor(colors.accentText)
        typeView.text = buildString {
            append(report.type)
            if (entry.count > 1) append("  ×").append(entry.count)
        }
        messageView.setTextColor(colors.text)
        messageView.text = report.message
        locationView.setTextColor(colors.muted)
        locationView.text = report.appFrame?.let(report.frames::getOrNull)?.location ?: report.location
        panel.contentDescription = "${report.phaseLabel}. ${report.shortType}. ${report.message}"

        val snippet = report.snippet
        sourceLabel.setTextColor(colors.muted)
        sourceLabel.visibility = if (snippet == null) GONE else VISIBLE
        sourceView.visibility = if (snippet == null) GONE else VISIBLE
        if (snippet != null) {
            sourceView.background = rounded(colors.code, dp(10).toFloat(), colors.border)
            sourceView.setTextColor(colors.text)
            sourceView.text = snippetText(snippet, colors)
        }

        stackLabel.setTextColor(colors.muted)
        stackLabel.visibility = if (report.frames.isEmpty()) GONE else VISIBLE
        renderStack(report, colors)

        footer.background = footerBackground(colors)
        styleFooterButton(dismissButton, colors, primary = false)
        styleFooterButton(copyButton, colors, primary = false)
        styleFooterButton(reloadButton, colors, primary = true)
    }

    private fun renderFallback(colors: Palette) {
        fallback.setBackgroundColor(colors.background)
        fallbackIcon.setTextColor(colors.accentText)
        fallbackIcon.background = rounded(colors.accentSoft, dp(32).toFloat())
        fallbackTitle.setTextColor(colors.text)
        fallbackMessage.setTextColor(colors.muted)
        fallbackRetry.setPadding(dp(28), 0, dp(28), 0)
        styleFooterButton(fallbackRetry, colors, primary = true)
        (fallbackRetry.background as? GradientDrawable)?.cornerRadius = dp(24).toFloat()
    }

    private fun snippetText(snippet: RuntimeErrorReport.Snippet, colors: Palette): CharSequence {
        val last = snippet.start + snippet.lines.size - 1
        val width = last.toString().length
        val gutter = (sourceView.paint.measureText("0") * (width + 3)).toInt()
        val builder = SpannableStringBuilder()
        snippet.lines.forEachIndexed { offset, line ->
            val number = snippet.start + offset
            val start = builder.length
            val prefix = number.toString().padStart(width) + " │ "
            builder.append(prefix).append(line.ifEmpty { " " })
            val end = builder.length
            builder.setSpan(LeadingMarginSpan.Standard(0, gutter), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            builder.setSpan(ForegroundColorSpan(colors.muted), start, start + prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (number == snippet.line) {
                builder.setSpan(BackgroundColorSpan(colors.highlight), start, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(StyleSpan(Typeface.BOLD), start + prefix.length, end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                builder.setSpan(ForegroundColorSpan(colors.accentText), start, start + prefix.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            if (offset < snippet.lines.lastIndex) builder.append('\n')
        }
        return builder
    }

    /**
     * App frames are always listed; consecutive framework/vendor/internal
     * frames collapse into one tappable "N framework frames" row.
     */
    private fun renderStack(report: RuntimeErrorReport, colors: Palette) {
        stackView.removeAllViews()
        var position = 0
        val frames = report.frames
        while (position < frames.size) {
            if (frames[position].isApp) {
                stackView.addView(frameView(frames[position], position == report.appFrame, colors), column(top = 4))
                position++
                continue
            }
            val groupStart = position
            while (position < frames.size && !frames[position].isApp) position++
            val group = frames.subList(groupStart, position)
            val open = groupStart in expandedGroups
            val label = context.resources.getQuantityString(
                R.plurals.pam_error_collapsed_frames,
                group.size,
                group.size,
            )
            stackView.addView(
                text(13f).apply {
                    text = context.getString(R.string.pam_error_frames_toggle, if (open) "▾" else "▸", label)
                    setTextColor(colors.muted)
                    setPadding(dp(12), dp(10), dp(12), dp(10))
                    minHeight = dp(44)
                    gravity = Gravity.CENTER_VERTICAL
                    isClickable = true
                    isFocusable = true
                    background = rounded(colors.surface, dp(8).toFloat())
                    setOnClickListener {
                        if (!expandedGroups.add(groupStart)) expandedGroups.remove(groupStart)
                        renderStack(report, palette)
                    }
                },
                column(top = 4),
            )
            if (open) group.forEach { stackView.addView(frameView(it, false, colors), column(top = 2)) }
        }
    }

    private fun frameView(frame: RuntimeErrorReport.Frame, primary: Boolean, colors: Palette): View =
        LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            background = if (primary) {
                rounded(colors.accentSoft, dp(8).toFloat())
            } else {
                rounded(if (frame.isApp) colors.surface else colors.background, dp(8).toFloat())
            }
            addView(text(13f, mono = true, bold = frame.isApp).apply {
                text = frame.call.ifEmpty { "{main}" }
                setTextColor(if (frame.isApp) colors.text else colors.muted)
            })
            addView(text(12f, mono = true).apply {
                text = frame.location
                setTextColor(if (primary) colors.accentText else colors.muted)
            }, column(top = 2))
        }

    private fun footerBackground(colors: Palette) = GradientDrawable().apply {
        setColor(colors.surface)
    }

    private fun styleFooterButton(view: TextView, colors: Palette, primary: Boolean) {
        view.setTextColor(if (primary) Color.WHITE else colors.text)
        view.background = if (primary) {
            rounded(colors.accent, dp(10).toFloat())
        } else {
            rounded(colors.button, dp(10).toFloat(), colors.border)
        }
    }

    private fun text(size: Float, mono: Boolean = false, bold: Boolean = false) = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, size)
        typeface = when {
            mono && bold -> Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            mono -> Typeface.MONOSPACE
            bold -> Typeface.DEFAULT_BOLD
            else -> Typeface.DEFAULT
        }
        includeFontPadding = true
    }

    private fun sectionLabel(label: String) = text(12f, bold = true).apply {
        text = label.uppercase()
        letterSpacing = 0.08f
    }

    private fun button(label: String) = TextView(context).apply {
        text = label
        gravity = Gravity.CENTER
        isClickable = true
        isFocusable = true
        setPadding(dp(10), 0, dp(10), 0)
        typeface = Typeface.DEFAULT_BOLD
    }

    private fun footerButton(label: String) = button(label).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }

    private fun rounded(color: Int, radius: Float, stroke: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
        if (stroke != null) setStroke(dp(1).coerceAtLeast(1), stroke)
    }

    private fun column(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT,
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private data class Palette(
        val background: Int,
        val surface: Int,
        val toast: Int,
        val code: Int,
        val button: Int,
        val border: Int,
        val text: Int,
        val muted: Int,
        val accent: Int,
        val accentText: Int,
        val accentSoft: Int,
        val highlight: Int,
    ) {
        companion object {
            fun of(dark: Boolean) = if (dark) {
                Palette(
                    background = 0xFF121214.toInt(),
                    surface = 0xFF1C1C20.toInt(),
                    toast = 0xFF26262B.toInt(),
                    code = 0xFF0B0B0D.toInt(),
                    button = 0xFF26262B.toInt(),
                    border = 0xFF34343A.toInt(),
                    text = 0xFFF4F4F5.toInt(),
                    muted = 0xFFA1A1AA.toInt(),
                    accent = 0xFFB3261E.toInt(),
                    accentText = 0xFFFF8A80.toInt(),
                    accentSoft = 0xFF3A1614.toInt(),
                    highlight = 0xFF4A1C1A.toInt(),
                )
            } else {
                Palette(
                    background = 0xFFFFFFFF.toInt(),
                    surface = 0xFFF4F4F5.toInt(),
                    toast = 0xFFFFFFFF.toInt(),
                    code = 0xFFF7F7F8.toInt(),
                    button = 0xFFFFFFFF.toInt(),
                    border = 0xFFE4E4E7.toInt(),
                    text = 0xFF18181B.toInt(),
                    muted = 0xFF52525B.toInt(),
                    accent = 0xFFB3261E.toInt(),
                    accentText = 0xFFB3261E.toInt(),
                    accentSoft = 0xFFFDECEA.toInt(),
                    highlight = 0xFFFCE1DE.toInt(),
                )
            }
        }
    }

    companion object {
        private const val TAG = "PamNativeErrors"
        private const val MAX_ENTRIES = 50

        /** `devErrorOverlay` from pam-native.json: 0 = off, 1 = debug builds, 2 = always. */
        fun developerMode(context: Context, debugBuild: Boolean): Boolean =
            when (context.resources.getInteger(R.integer.pam_dev_error_overlay)) {
                0 -> false
                2 -> true
                else -> debugBuild
            }
    }
}
