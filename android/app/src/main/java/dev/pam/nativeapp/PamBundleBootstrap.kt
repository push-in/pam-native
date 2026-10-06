package dev.pam.nativeapp

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask

/**
 * Installs the embedded application bundle once per process, starting as
 * early as the process does (see [PamBundleInstallProvider]): on the first
 * launch after an install or update the extraction overlaps Application and
 * Activity creation instead of starting after them.
 */
internal object PamBundleBootstrap {
    private var pending: FutureTask<File>? = null

    @Volatile
    var lastInstallMillis = -1L
        private set

    fun start(context: Context): FutureTask<File> = synchronized(this) {
        pending ?: run {
            val application = context.applicationContext ?: context
            FutureTask {
                val started = SystemClock.elapsedRealtime()
                AssetInstaller(application).install().also {
                    lastInstallMillis = SystemClock.elapsedRealtime() - started
                }
            }.also { task ->
                pending = task
                Thread(
                    {
                        // Gates the first frame on a fresh install: above the
                        // default priority while Application/Activity start.
                        runCatching {
                            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DISPLAY)
                        }
                        task.run()
                    },
                    "pam-asset-install",
                ).start()
            }
        }
    }

    /** The embedded entry (`releases/<version>/index.php`); a failure is retried next call. */
    fun embeddedEntry(context: Context): File {
        val task = start(context)
        return try {
            task.get()
        } catch (error: ExecutionException) {
            synchronized(this) {
                if (pending === task) pending = null
            }
            throw error.cause ?: error
        }
    }
}

/**
 * Starts [PamBundleBootstrap] when the application process starts (content
 * providers are created before `Application.onCreate` and the first Activity).
 */
class PamBundleInstallProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        context?.let { context ->
            runCatching { PamBundleBootstrap.start(context) }.onFailure {
                Log.w("PamNative", "Cannot start the application bundle install", it)
            }
        }
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}
