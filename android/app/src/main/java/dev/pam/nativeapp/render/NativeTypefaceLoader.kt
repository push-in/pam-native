package dev.pam.nativeapp.render

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.Log
import java.util.concurrent.ConcurrentHashMap

internal class NativeTypefaceLoader(context: Context) {
    private val assets = context.assets
    private val assetFonts = ConcurrentHashMap<String, Typeface>()

    fun resolve(family: String?, weight: Int, italic: Boolean): Typeface {
        val resolvedWeight = weight.coerceIn(1, 1000)
        val assetPath = family?.let(::normalizedFontAssetPath)
        if (assetPath != null) {
            val key = "$assetPath:$resolvedWeight:$italic"
            return assetFonts[key] ?: runCatching {
                Typeface.Builder(assets, assetPath)
                    .setWeight(resolvedWeight)
                    .setItalic(italic)
                    .setFontVariationSettings("'wght' $resolvedWeight")
                    .build()
            }.onFailure {
                Log.w(TAG, "Unable to load packaged font $assetPath", it)
            }.getOrNull()?.also { assetFonts.putIfAbsent(key, it) }
                ?: systemTypeface(null, resolvedWeight, italic)
        }
        return systemTypeface(family, resolvedWeight, italic)
    }

    private fun systemTypeface(family: String?, weight: Int, italic: Boolean): Typeface {
        val base = Typeface.create(family, Typeface.NORMAL)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(base, weight, italic)
        }
        val style = when {
            weight >= 600 && italic -> Typeface.BOLD_ITALIC
            weight >= 600 -> Typeface.BOLD
            italic -> Typeface.ITALIC
            else -> Typeface.NORMAL
        }
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
