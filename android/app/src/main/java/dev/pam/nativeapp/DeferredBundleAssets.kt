package dev.pam.nativeapp

/** One bounded streaming worker, started after a real frame and stopped on pause. */
internal object DeferredBundleAssets {
    private var work: (() -> Unit)? = null
    private var worker: Thread? = null
    private var visible = false

    @Synchronized
    fun enqueue(task: () -> Unit) {
        work = task
        startIfVisible()
    }

    @Synchronized
    fun resume() {
        visible = true
        startIfVisible()
    }

    @Synchronized
    fun pause() {
        visible = false
        worker?.interrupt()
    }

    private fun startIfVisible() {
        if (!visible || worker != null) return
        val task = work ?: return
        worker = Thread({
            var complete = false
            var interrupted = false
            try {
                task()
                complete = true
            } catch (_: InterruptedException) {
                interrupted = true
            } catch (error: Exception) {
                // A failed asset copy retries on the next foreground/start;
                // native consumers still read the original APK asset.
                android.util.Log.w("PamNative", "Deferred asset install failed", error)
            } finally {
                synchronized(this) {
                    if (complete && work === task) work = null
                    worker = null
                    // onResume may have raced a cancelled worker's shutdown.
                    if (interrupted) startIfVisible()
                }
            }
        }, "pam-deferred-assets").apply { isDaemon = true; start() }
    }
}
