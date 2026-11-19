package dev.pam.nativeapp.text

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Rewrites a text's spans before PAM measures and draws it (`Text`, both
 * for layout and on screen). A plugin installs one to add what the core
 * does not ship, e.g. EmojiCompat spans so emoji newer than the system font
 * draw on Android 7-12 instead of tofu:
 *
 * ```kotlin
 * PamTextProcessors.install { text ->
 *     val emoji = EmojiCompat.get()
 *     if (emoji.loadState == EmojiCompat.LOAD_STATE_SUCCEEDED) emoji.process(text) ?: text else text
 * }
 * // When the emoji font finishes loading:
 * PamTextProcessors.changed()
 * ```
 *
 * [PamTextProcessor.process] runs on the thread that lays text out (not
 * always the main thread) and on every measured text: it must be thread
 * safe, fast, and return the same length-preserving result for the same
 * input (spans only; the characters must not change).
 */
public fun interface PamTextProcessor {
    public fun process(text: CharSequence): CharSequence
}

public object PamTextProcessors {
    @Volatile
    private var installed: PamTextProcessor? = null
    private val listeners = CopyOnWriteArraySet<Runnable>()
    private val main by lazy { Handler(Looper.getMainLooper()) }

    /** Installs (or, with null, removes) the processor and re-lays out every text. */
    @JvmStatic
    public fun install(processor: PamTextProcessor?) {
        installed = processor
        changed()
    }

    /**
     * The installed processor now gives other results (its font loaded): the
     * host measures every text again and rebuilds the mounted views once,
     * like a font-scale change.
     */
    @JvmStatic
    public fun changed() {
        if (listeners.isEmpty()) return
        main.post { listeners.forEach(Runnable::run) }
    }

    /** [text] through the installed processor; the text itself without one or when it fails. */
    @JvmStatic
    public fun process(text: CharSequence): CharSequence {
        val processor = installed ?: return text
        if (text.isEmpty()) return text
        val result = runCatching { processor.process(text) }.getOrNull() ?: return text
        return if (result.length == text.length) result else text
    }

    /** True while a processor is installed. */
    @JvmStatic
    public val active: Boolean get() = installed != null

    /** Host only: called on the main thread after [changed]. */
    @JvmStatic
    public fun addListener(listener: Runnable) {
        listeners += listener
    }

    @JvmStatic
    public fun removeListener(listener: Runnable) {
        listeners -= listener
    }
}
