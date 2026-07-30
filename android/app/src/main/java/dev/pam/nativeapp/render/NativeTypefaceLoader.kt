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
    val assetPath = normalizedPamAssetPath(family) ?: return null
    require(assetPath.endsWith(".ttf", true) || assetPath.endsWith(".otf", true)) {
        "Font asset must be a TTF or OTF file"
    }
    return assetPath
}
