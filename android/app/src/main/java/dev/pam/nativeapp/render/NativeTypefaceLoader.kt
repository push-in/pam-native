package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

internal class NativeTypefaceLoader(context: Context) {
    private val assets = context.applicationContext.assets
    private val assetFonts = ConcurrentHashMap<String, Typeface>()

    fun resolve(family: String?, style: Int): Typeface {
        if (family.isNullOrBlank()) {
            return Typeface.defaultFromStyle(style)
        }
        val assetPath = normalizedFontAssetPath(family)
            ?: return Typeface.create(family, style)
        val base = assetFonts[assetPath] ?: runCatching {
            Typeface.createFromAsset(assets, assetPath)
        }.onFailure {
            Log.w(TAG, "Unable to load packaged font $assetPath", it)
        }.getOrNull()?.also {
            assetFonts.putIfAbsent(assetPath, it)
        } ?: return Typeface.defaultFromStyle(style)

        return Typeface.create(base, style)
    }

    private companion object {
        const val TAG = "PamNativeFonts"
    }
}

internal fun normalizedFontAssetPath(family: String): String? {
    if (!family.startsWith(ASSET_SCHEME)) {
        return null
    }
    val relative = family.removePrefix(ASSET_SCHEME).trimStart('/')
    require(relative.isNotBlank()) { "Font asset path cannot be empty" }
    require('\\' !in relative && '\u0000' !in relative) {
        "Font asset path contains invalid characters"
    }
    require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Font asset path cannot contain empty or traversal segments"
    }
    require(relative.endsWith(".ttf", true) || relative.endsWith(".otf", true)) {
        "Font asset must be a TTF or OTF file"
    }
    return if (relative.startsWith("pam/")) relative else "pam/$relative"
}

private const val ASSET_SCHEME = "asset://"
