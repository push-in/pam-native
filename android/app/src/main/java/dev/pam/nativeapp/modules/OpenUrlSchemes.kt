package dev.pam.nativeapp.modules

import java.util.Locale

/**
 * The URI schemes `Linking::open()`/`canOpen()` hand to other apps: https,
 * http, mailto, tel and geo, plus the app's `linking.openSchemes` from
 * pam-native.json (R.array.pam_open_url_schemes, e.g. otpauth). Schemes that
 * reach local files, other apps' components or script are never accepted,
 * even when listed. Matching ignores case, like Uri.normalizeScheme().
 */
internal class OpenUrlSchemes(extra: Iterable<String> = emptyList()) {
    private val allowed: Set<String> =
        DEFAULT + extra.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() && it !in BLOCKED }

    fun accepts(scheme: String?): Boolean = scheme != null && scheme.lowercase(Locale.ROOT) in allowed

    companion object {
        val DEFAULT = setOf("https", "http", "mailto", "tel", "geo")
        val BLOCKED = setOf(
            "file", "content", "intent", "android-app", "javascript", "vbscript", "data", "blob", "about", "jar",
        )
    }
}
