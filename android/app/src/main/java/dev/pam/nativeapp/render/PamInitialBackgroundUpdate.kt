package dev.pam.nativeapp.render

/** Last background request while a container receives its initial properties. */
internal class PamInitialBackgroundUpdate {
    var requested = false
        private set
    var backgroundColor: Int? = null
        private set
    var borderColor: Int? = null
        private set

    fun request(backgroundColor: Int?, borderColor: Int?) {
        requested = true
        // Keep the last native state override too, if a focus/press callback
        // runs during initialization. Later requests replace it as before.
        this.backgroundColor = backgroundColor
        this.borderColor = borderColor
    }
}
