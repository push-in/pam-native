package dev.pam.nativeapp.render

internal fun normalizedPamAssetPath(source: String): String? {
    if (!source.startsWith(ASSET_SCHEME, ignoreCase = true)) {
        return null
    }

    val relative = source
        .substring(ASSET_SCHEME.length)
        .trimStart('/')
    require(relative.isNotBlank()) { "Packaged asset path cannot be empty" }
    require('\\' !in relative && '\u0000' !in relative) {
        "Packaged asset path contains invalid characters"
    }
    require('?' !in relative && '#' !in relative) {
        "Packaged asset path cannot contain a query or fragment"
    }
    require(relative.split('/').none { it.isBlank() || it == "." || it == ".." }) {
        "Packaged asset path cannot contain empty or traversal segments"
    }

    return if (relative.startsWith("$PAM_ASSET_ROOT/")) {
        relative
    } else {
        "$PAM_ASSET_ROOT/$relative"
    }
}

private const val ASSET_SCHEME = "asset://"
private const val PAM_ASSET_ROOT = "pam"
