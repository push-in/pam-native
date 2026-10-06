use std::ffi::OsStr;
use std::path::Path;

/// The Composer SDK includes host tooling beside PHP. Native compilation reads
/// that tooling from the project before staging; devices only need the PHP SDK.
/// Keep this scoped to our own package so application/plugin resources survive.
pub(crate) fn is_sdk_host_material(path: &Path) -> bool {
    let Ok(relative) = path.strip_prefix("vendor/pushinbr/pam-native") else {
        return false;
    };
    matches!(
        relative.iter().next().and_then(OsStr::to_str),
        Some(
            "android"
                | "benchmarks"
                | "certification"
                | "crates"
                | "docs"
                | "editors"
                | "examples"
                | "ios"
                | "ios-host"
                | "native"
                | "runtime"
                | "scripts"
        )
    )
}
