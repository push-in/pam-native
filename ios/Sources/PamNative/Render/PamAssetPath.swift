import Foundation

enum PamAssetPathError: Error {
    case empty
    case invalidCharacters
    case uriSuffix
    case invalidSegment
}

func normalizedPamAssetPath(_ source: String) throws -> String? {
    let scheme = "asset://"
    guard source.range(
        of: scheme,
        options: [.anchored, .caseInsensitive]
    ) != nil else {
        return nil
    }

    let relative = source
        .dropFirst(scheme.count)
        .drop(while: { $0 == "/" })
    guard !relative.isEmpty else {
        throw PamAssetPathError.empty
    }
    guard !relative.contains("\\") && !relative.contains("\0") else {
        throw PamAssetPathError.invalidCharacters
    }
    guard !relative.contains("?") && !relative.contains("#") else {
        throw PamAssetPathError.uriSuffix
    }
    guard relative.split(separator: "/", omittingEmptySubsequences: false).allSatisfy({
        !$0.isEmpty && $0 != "." && $0 != ".."
    }) else {
        throw PamAssetPathError.invalidSegment
    }

    let path = String(relative)
    return path.hasPrefix("pam/") ? path : "pam/\(path)"
}

/// File URL of a normalized `pam/<relative>` asset path inside the app.
/// The iOS host stages the project in `PamBundle/` (Android packages it under
/// `assets/pam/`), so `PamBundle/<relative>` is tried first and the literal
/// `pam/<relative>` layout stays supported for hosts and tests that use it.
func pamAssetFileURL(_ path: String, resourceRoot: URL? = Bundle.main.resourceURL) -> URL? {
    guard let resourceRoot else { return nil }
    var candidates: [String] = []
    if path.hasPrefix("pam/") {
        candidates.append("PamBundle/" + path.dropFirst("pam/".count))
    }
    candidates.append(path)
    let fileManager = FileManager.default
    return candidates
        .map { resourceRoot.appendingPathComponent($0, isDirectory: false) }
        .first { fileManager.fileExists(atPath: $0.path) }
}
