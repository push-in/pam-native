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
        conventionalFontAsset(family, resolvedWeight, italic)?.let { (path, exactStyle) ->
            val key = "convention:$path:$resolvedWeight:$italic"
            assetFonts[key]?.let { return it }
            runCatching {
                // A file that already is the requested weight/style is used
                // as is (no fake bold); otherwise the platform synthesizes the
                // style from the closest file, like React Native's
                // Typeface.create(typeface, weight, italic).
                val base = Typeface.Builder(assets, path).build()
                when {
                    exactStyle -> base
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.P -> Typeface.create(base, resolvedWeight, italic)
                    else -> Typeface.create(
                        base,
                        when {
                            resolvedWeight >= 600 && italic -> Typeface.BOLD_ITALIC
                            resolvedWeight >= 600 -> Typeface.BOLD
                            italic -> Typeface.ITALIC
                            else -> Typeface.NORMAL
                        },
                    )
                }
            }.getOrNull()?.let { face ->
                assetFonts.putIfAbsent(key, face)
                return face
            }
        }
        return systemTypeface(family, resolvedWeight, italic)
    }

    private val conventionalPaths = ConcurrentHashMap<String, String>()

    private fun assetExists(path: String): Boolean =
        conventionalPaths.getOrPut(path) {
            if (runCatching { assets.open(path).close() }.isSuccess) "1" else "0"
        } == "1"

    /**
     * React Native Android font-file conventions for a bare `fontFamily`:
     * `assets/fonts/{Family}-{Weight}.ttf` (e.g. SpaceGrotesk-SemiBold),
     * `{Family}_{weight}` and the classic `{Family}_bold/_italic/_bold_italic`
     * suffixes, then `{Family}.ttf`. Returns the asset path and whether it
     * exactly matches the requested weight/style.
     */
    private fun conventionalFontAsset(family: String?, weight: Int, italic: Boolean): Pair<String, Boolean>? {
        if (family.isNullOrBlank() || family.lowercase() in SYSTEM_FAMILIES || family.startsWith("sans-serif")) return null
        if (!family.all { it.isLetterOrDigit() || it == ' ' || it == '_' || it == '-' }) return null
        val compact = family.replace(" ", "")
        val rounded = ((weight + 50) / 100 * 100).coerceIn(100, 900)
        val weightName = WEIGHT_NAMES.getValue(rounded)
        val candidates = buildList {
            val italicSuffix = if (italic) "Italic" else ""
            add("$compact-${if (italic && rounded == 400) "Italic" else weightName + italicSuffix}" to true)
            add("${compact}_$rounded${if (italic) "_italic" else ""}" to true)
            if (rounded >= 600) add("${compact}_bold${if (italic) "_italic" else ""}" to true)
            if (italic && rounded < 600) add("${compact}_italic" to true)
            if (rounded == 400 && !italic) add("$compact-Regular" to true)
            add(compact to (rounded == 400 && !italic))
        }
        for ((name, exact) in candidates) {
            for (directory in FONT_DIRECTORIES) {
                for (extension in listOf("ttf", "otf")) {
                    val path = "$directory/$name.$extension"
                    if (assetExists(path)) return path to exact
                }
            }
        }
        return null
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

    companion object {
        private const val TAG = "PamNativeFonts"
        private val SYSTEM_FAMILIES = setOf("serif", "monospace", "roboto", "system", "cursive", "casual", "system-ui")
        private val FONT_DIRECTORIES = listOf("pam/assets/fonts", "pam/fonts")
        private val WEIGHT_NAMES = mapOf(
            100 to "Thin",
            200 to "ExtraLight",
            300 to "Light",
            400 to "Regular",
            500 to "Medium",
            600 to "SemiBold",
            700 to "Bold",
            800 to "ExtraBold",
            900 to "Black",
        )

        private val sharedInstances = java.util.WeakHashMap<android.content.res.AssetManager, NativeTypefaceLoader>()

        /** One loader per asset source so drawing and measurement share typefaces. */
        fun shared(context: Context): NativeTypefaceLoader = synchronized(sharedInstances) {
            sharedInstances.getOrPut(context.assets) { NativeTypefaceLoader(context) }
        }
    }
}

internal fun normalizedFontAssetPath(family: String): String? {
    val assetPath = normalizedPamAssetPath(family) ?: return null
    require(assetPath.endsWith(".ttf", true) || assetPath.endsWith(".otf", true)) {
        "Font asset must be a TTF or OTF file"
    }
    return assetPath
}
