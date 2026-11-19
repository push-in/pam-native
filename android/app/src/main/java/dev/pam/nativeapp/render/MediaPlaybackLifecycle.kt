package dev.pam.nativeapp.render

/** Main-thread playback intent; the media worker reads only [mayPlay]. */
internal class MediaPlaybackLifecycle {
    private var autoPlay = false
    private var requested = false
    private var hostActive = true
    private var ownerActive = true
    private var attached = false
    private var visible = false
    /** Visible autoplay: the native policy's decision (always true without it). */
    private var visibleGate = true
    /** Audio focus: false while another app holds it for a moment (a call). */
    private var focusGate = true

    /** Play intent (autoPlay or a native play request), independent of host and visibility. */
    val wantsPlay: Boolean get() = requested

    @Volatile
    var mayPlay = false
        private set

    /** Everything but audio focus lets the player play: it should hold focus (with sound). */
    var wantsFocus = false
        private set

    fun autoPlay(value: Boolean) {
        // Reapplying a property must not undo a pause from native controls.
        if (autoPlay != value) {
            autoPlay = value
            requested = value
        }
        update()
    }

    fun request(value: Boolean) { requested = value; update() }
    fun hostActive(value: Boolean) { hostActive = value; update() }
    fun ownerActive(value: Boolean) { ownerActive = value; update() }
    fun visibility(attached: Boolean, visible: Boolean) {
        this.attached = attached
        this.visible = visible
        update()
    }

    fun visibleGate(value: Boolean) { visibleGate = value; update() }
    fun focusGate(value: Boolean) { focusGate = value; update() }

    fun completed(looping: Boolean) {
        if (!looping) requested = false
        update()
    }

    private fun update() {
        wantsFocus = requested && hostActive && ownerActive && attached && visible && visibleGate
        mayPlay = wantsFocus && focusGate
    }
}
