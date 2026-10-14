package dev.pam.nativeapp.render

/**
 * Current page of a pager/carousel. [advance] reports the previous page only
 * when the page actually changed, so scroll steps inside one page are free.
 */
internal class PamPageTracker {
    private var current = UNKNOWN

    fun reset(page: Int) {
        current = page.coerceAtLeast(0)
    }

    /** Returns the previous page when [page] differs from the current one. */
    fun advance(page: Int): Int? {
        val next = page.coerceAtLeast(0)
        if (current == UNKNOWN) {
            current = next
            return null
        }
        if (next == current) return null
        val previous = current
        current = next
        return previous
    }

    private companion object {
        const val UNKNOWN = -1
    }
}
