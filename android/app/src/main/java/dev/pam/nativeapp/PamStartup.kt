package dev.pam.nativeapp

import android.content.Context
import android.os.Process
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.Future
import java.util.concurrent.FutureTask

/**
 * Cold-start work that does not need a window: loading the engine library
 * (several MB of relocations) and resolving the PHP bundle (embedded
 * extraction/verification or the active OTA slot). [PamActivity] starts it
 * as the very first thing in onCreate, so it overlaps the native view setup
 * and the runtime can boot PHP before the window's first traversal.
 */
internal object PamStartup {
    private const val LIBRARY = "pam_native_android"
    private const val LOG_TAG = "PamNativePerf"

    private val libraryLock = Any()

    @Volatile
    private var libraryLoaded = false

    private var bundle: Future<File>? = null

    /** Starts (once per process, again after a failure) the bundle resolution. */
    @Synchronized
    fun prepare(context: Context): Future<File> {
        bundle?.let { pending ->
            if (!pending.isDone || runCatching { pending.get() }.isSuccess) return pending
        }
        val application = context.applicationContext
        val task = FutureTask {
            val started = SystemClock.elapsedRealtime()
            loadNativeLibrary()
            val loaded = SystemClock.elapsedRealtime()
            // The embedded install already started with the process
            // (PamBundleInstallProvider); this only waits for it.
            val entry = ActiveUpdateInstaller(application).resolve(
                PamBundleBootstrap.embeddedEntry(application),
            )
            if (BuildConfig.DEBUG || BuildConfig.BUILD_TYPE == "benchmark") {
                Log.d(
                    LOG_TAG,
                    "libraryLoadMs=${loaded - started} " +
                        "bundleInstallMs=${PamBundleBootstrap.lastInstallMillis} " +
                        "bundleWaitMs=${SystemClock.elapsedRealtime() - loaded}",
                )
            }
            entry
        }
        bundle = task
        Thread(
            {
                // The PHP bundle gates the first frame: run it above the
                // default background priority while the UI thread inflates.
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY) }
                task.run()
            },
            "pam-startup",
        ).start()
        return task
    }

    /** Blocks until the bundle is resolved; rethrows its failure cause. */
    fun awaitEntry(prepared: Future<File>): File = try {
        prepared.get()
    } catch (failure: ExecutionException) {
        throw failure.cause ?: failure
    }

    /** Idempotent; every JNI entry point of [PamRuntime] runs after this. */
    fun loadNativeLibrary() {
        if (libraryLoaded) return
        synchronized(libraryLock) {
            if (libraryLoaded) return
            System.loadLibrary(LIBRARY)
            libraryLoaded = true
        }
    }
}
