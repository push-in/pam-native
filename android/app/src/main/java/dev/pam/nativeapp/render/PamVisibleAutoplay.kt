package dev.pam.nativeapp.render

import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewTreeObserver
import androidx.recyclerview.widget.RecyclerView

/**
 * One player under the visible-autoplay policy, as measured after a frame.
 *
 * @property fraction share of the player's area on screen (0..1)
 * @property threshold `autoPlayVisible`: share needed to start
 * @property idle every scrolling list holding the player is at rest
 * @property scope players with the same non-null scope (list + group) play one at a time
 * @property top on-screen top of the visible part (topmost wins a group)
 * @property active the player currently holds the policy's grant
 */
internal data class PamAutoplayCandidate(
    val fraction: Float,
    val threshold: Float,
    val idle: Boolean,
    val scope: Any?,
    val top: Int,
    val active: Boolean,
)

/**
 * Visible-autoplay decision (pure; JVM tested). A player starts only at rest
 * with at least its threshold on screen; a playing one keeps playing, also
 * while the list moves, until it falls below `threshold - HYSTERESIS`. Within
 * a scope one player plays: the one already playing while it still meets its
 * threshold, else the topmost that does (React Native's first viewable
 * video), else the one already playing while it stays above its floor.
 */
internal object PamAutoplayPolicy {
    const val HYSTERESIS = 0.2f
    private const val EPSILON = 0.001f

    fun floor(threshold: Float): Float = maxOf(0.01f, threshold - HYSTERESIS)

    private fun meets(candidate: PamAutoplayCandidate) = candidate.fraction + EPSILON >= candidate.threshold
    private fun keeps(candidate: PamAutoplayCandidate) =
        candidate.active && candidate.fraction > 0f && candidate.fraction + EPSILON >= floor(candidate.threshold)

    fun choose(candidates: List<PamAutoplayCandidate>): BooleanArray {
        val chosen = BooleanArray(candidates.size)
        var scopes: LinkedHashMap<Any, MutableList<Int>>? = null
        candidates.forEachIndexed { index, candidate ->
            val scope = candidate.scope
            if (scope == null) {
                chosen[index] = keeps(candidate) || (candidate.idle && meets(candidate))
            } else {
                if (scopes == null) scopes = LinkedHashMap()
                scopes!!.getOrPut(scope) { ArrayList(2) }.add(index)
            }
        }
        scopes?.values?.forEach { members ->
            val kept = members.filter { keeps(candidates[it]) }.minByOrNull { candidates[it].top }
            val idle = members.all { candidates[it].idle }
            val winner = when {
                !idle -> kept
                kept != null && meets(candidates[kept]) -> kept
                else -> members.filter { meets(candidates[it]) }.minByOrNull { candidates[it].top } ?: kept
            }
            if (winner != null) chosen[winner] = true
        }
        return chosen
    }
}

/**
 * Main-thread coordinator of every attached player with `autoPlayVisible`.
 * Scrolls, layouts and list settles schedule one evaluation after the frame
 * (coalesced); it measures the players, runs [PamAutoplayPolicy] and grants
 * or withdraws playback on each. No PHP round-trip.
 */
internal object PamVisibleAutoplay {
    private val members = LinkedHashSet<PamMediaView>()
    private val main = Handler(Looper.getMainLooper())
    private var scheduled = false
    private val rect = android.graphics.Rect()
    private val evaluation = Runnable {
        scheduled = false
        evaluate()
    }

    fun join(view: PamMediaView) {
        if (members.add(view)) view.installAutoplayObservers()
        schedule()
    }

    fun leave(view: PamMediaView) {
        if (members.remove(view)) view.removeAutoplayObservers()
    }

    fun schedule() {
        if (scheduled || members.isEmpty()) return
        scheduled = true
        main.post(evaluation)
    }

    /** A scrolling list came to rest: players may start. */
    fun listSettled() = schedule()

    internal val size: Int get() = members.size

    private fun evaluate() {
        if (members.isEmpty()) return
        val views = members.toTypedArray()
        // While the lists move nothing starts: only playing players are measured.
        if (views.none { it.autoplayGranted } && views.none { listsIdle(it) }) return
        val candidates = views.map { view ->
            val idle = listsIdle(view)
            val fraction = if (idle || view.autoplayGranted) view.visibleFraction(rect) else 0f
            PamAutoplayCandidate(
                fraction = fraction,
                threshold = view.autoplayThreshold,
                idle = idle,
                scope = view.autoplayGroupName.takeIf { it.isNotEmpty() }?.let { it to nearestList(view) },
                top = if (fraction > 0f) rect.top else Int.MAX_VALUE,
                active = view.autoplayGranted,
            )
        }
        val chosen = PamAutoplayPolicy.choose(candidates)
        // Withdraw first, so two players of one group never play together.
        views.forEachIndexed { index, view -> if (!chosen[index]) view.grantAutoplay(false) }
        views.forEachIndexed { index, view -> if (chosen[index]) view.grantAutoplay(true) }
    }

    private fun listsIdle(view: View): Boolean {
        var parent = view.parent
        while (parent != null) {
            if (parent is RecyclerView && parent.scrollState != RecyclerView.SCROLL_STATE_IDLE) return false
            parent = parent.parent
        }
        return true
    }

    private fun nearestList(view: View): Any? {
        var parent = view.parent
        while (parent != null) {
            if (parent is PamRecyclerList) return parent
            parent = parent.parent
        }
        return null
    }

    /** Per-view observers: they only schedule (cheap, coalesced per frame). */
    internal class Observers : ViewTreeObserver.OnScrollChangedListener, ViewTreeObserver.OnGlobalLayoutListener {
        var observer: ViewTreeObserver? = null
        override fun onScrollChanged() = schedule()
        override fun onGlobalLayout() = schedule()
    }
}
