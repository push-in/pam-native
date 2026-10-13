package dev.pam.nativeapp

/** Reduces root/dialog insets to transitions; animation frames never cross into PHP. */
internal class KeyboardVisibility(private val changed: (Boolean) -> Unit) {
    private val visibleSurfaces = mutableSetOf<Long>()
    private var visible = false
    private var active = true

    fun update(surface: Long, bottom: Float) {
        if (!active) return
        if (bottom > 0f) visibleSurfaces.add(surface) else visibleSurfaces.remove(surface)
        publish(visibleSurfaces.isNotEmpty())
    }

    fun setActive(value: Boolean) {
        active = value
        if (!value) {
            visibleSurfaces.clear()
            publish(false)
        }
    }

    fun publishCurrent() { changed(visible) }

    private fun publish(value: Boolean) {
        if (visible == value) return
        visible = value
        changed(value)
    }
}
