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
