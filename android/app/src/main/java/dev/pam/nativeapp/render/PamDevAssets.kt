package dev.pam.nativeapp.render

import android.content.res.AssetManager
import java.io.File
import java.io.InputStream

/**
 * During `pam dev` the project tree the runtime runs is synced to
 * `files/pam/dev/live`; the APK still holds the assets of the last build.
 * Packaged `asset://` paths (`pam/<project path>`) resolve from the live tree
 * first, so an image or font added while `pam dev` runs shows up on the next
 * reload instead of after a rebuild. Release builds never set [liveRoot].
 */
internal object PamDevAssets {
    @Volatile
    var liveRoot: File? = null

    /** The synced file for packaged [path] (`pam/assets/...`), when one exists. */
    fun file(path: String): File? {
        val root = liveRoot ?: return null
        val relative = path.removePrefix("pam/")
        if (relative == path || relative.isEmpty()) return null
        return File(root, relative).takeIf { it.isFile }
    }

    fun open(assets: AssetManager, path: String): InputStream =
        file(path)?.inputStream() ?: assets.open(path)

    fun exists(assets: AssetManager, path: String): Boolean =
        file(path) != null || runCatching { assets.open(path).close() }.isSuccess
}
