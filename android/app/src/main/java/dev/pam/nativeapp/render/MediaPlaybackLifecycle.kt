package dev.pam.nativeapp.render

/** Main-thread playback intent; the media worker reads only [mayPlay]. */
internal class MediaPlaybackLifecycle {
    private var autoPlay = false
    private var requested = false
    private var hostActive = true
    private var attached = false
    private var visible = false

    @Volatile
    var mayPlay = false
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
    fun visibility(attached: Boolean, visible: Boolean) {
        this.attached = attached
        this.visible = visible
        update()
    }

    fun completed(looping: Boolean) {
        if (!looping) requested = false
        update()
    }

    private fun update() { mayPlay = requested && hostActive && attached && visible }
}
