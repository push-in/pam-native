package dev.pam.nativeapp.modules

/** A retained PHP runtime outlives its Activity; process event waiters must survive rebind. */
internal interface SurfaceRetirableModule : AutoCloseable {
    /** Release only resources owned by the previous surface. */
    fun retireSurface()
}
