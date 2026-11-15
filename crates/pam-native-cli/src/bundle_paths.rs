use std::ffi::OsStr;
use std::path::Path;

/// The Composer SDK includes host tooling beside PHP. Native compilation reads
/// that tooling from the project before staging; devices only need the PHP SDK.
/// Keep this scoped to our own packages so application resources and
/// third-party Composer packages survive untouched.
pub(crate) fn is_sdk_host_material(path: &Path) -> bool {
    let Ok(relative) = path.strip_prefix("vendor/pushinbr") else {
        return false;
    };
    let parts = relative
        .iter()
        .map(|part| part.to_str().unwrap_or_default())
        .collect::<Vec<_>>();
    let [package, rest @ ..] = parts.as_slice() else {
        return false;
    };
    let Some((name, directories)) = rest.split_last() else {
        return false;
    };
    if *package == "pam-native" && is_core_host_directory(directories, name) {
        return true;
    }
    // Plugin native sources are compiled from the project's vendor tree
    // (`sourceDirs`/`resourceDirs` point there), never from the bundle.
    directories.first().is_some_and(|first| {
        matches!(
            *first,
            "android" | "ios" | "tests" | "docs" | "benchmarks" | "scripts" | "examples"
        )
    }) || is_host_only_file(name)
}

fn is_core_host_directory(directories: &[&str], name: &str) -> bool {
    let first = directories.first().copied().unwrap_or(name);
    matches!(
        first,
        "certification" | "crates" | "editors" | "ios-host" | "native" | "runtime"
    ) || match directories {
        // The web/TV compiler is not autoloaded on Android or iOS.
        ["packages", "web", ..] => true,
        // bin/ stays: the PAM toolchain resolves the package's Composer
        // scripts there when the build host runs PHP inside the bundle.
        ["packages", "native", "tests", ..] => true,
        _ => false,
    } || matches!(name, "Cargo.lock" | "Cargo.toml")
}

/// Documentation, licences, lock files and build/tooling sources that no
/// device process reads.
fn is_host_only_file(name: &str) -> bool {
    let upper = name.to_ascii_uppercase();
    upper.starts_with("LICENSE")
        || upper.starts_with("LICENCE")
        || name == "composer.lock"
        || name.starts_with("phpstan")
        || matches!(
            Path::new(name).extension().and_then(OsStr::to_str),
            Some(
                "md" | "kt"
                    | "kts"
                    | "swift"
                    | "py"
                    | "sh"
                    | "patch"
                    | "neon"
                    | "gradle"
                    | "entitlements"
                    | "stub"
            )
        )
}

#[cfg(test)]
mod tests {
    use super::is_sdk_host_material;
    use std::path::Path;

    #[test]
    fn device_bundles_drop_host_only_package_material() {
        for path in [
            "vendor/pushinbr/pam-native/android/app/build.gradle.kts",
            "vendor/pushinbr/pam-native/crates/pam-native-engine/src/layout.rs",
            "vendor/pushinbr/pam-native/Cargo.lock",
            "vendor/pushinbr/pam-native/RELATORIO.md",
            "vendor/pushinbr/pam-native/LICENSE",
            "vendor/pushinbr/pam-native/packages/web/src/Compiler.php",
            "vendor/pushinbr/pam-native/packages/web/resources/runtime.js",
            "vendor/pushinbr/pam-native/packages/native/tests/Fixtures/crypto-vectors.json",
            "vendor/pushinbr/pam-native/packages/native/README.md",
            "vendor/pushinbr/pam-native-lottie/android/src/main/kotlin/Lottie.kt",
            "vendor/pushinbr/pam-native-lottie/android/build.gradle.kts",
            "vendor/pushinbr/pam-native-auth/ios/Sources/Auth.swift",
            "vendor/pushinbr/pam-native-auth/tests/ios-host/generate-project.py",
            "vendor/pushinbr/pam-native-svg/docs/svg.md",
            "vendor/pushinbr/pam-native-observability/composer.lock",
            "vendor/pushinbr/pam-native-media-editor/phpstan.neon",
            "vendor/pushinbr/pam-native-toast/CHANGELOG.md",
        ] {
            assert!(is_sdk_host_material(Path::new(path)), "{path}");
        }
    }

    #[test]
    fn device_bundles_keep_runtime_package_material() {
        for path in [
            "vendor/pushinbr/pam-native/composer.json",
            "vendor/pushinbr/pam-native/packages/native/src/App.php",
            "vendor/pushinbr/pam-native/packages/native/bin/pam-native",
            "vendor/pushinbr/pam-native/packages/native/src/Testing/functions.php",
            "vendor/pushinbr/pam-native/packages/native/resources/pam-native.schema.json",
            "vendor/pushinbr/pam-native-lottie/pam-native.plugin.json",
            "vendor/pushinbr/pam-native-lottie/pam-native.idl.json",
            "vendor/pushinbr/pam-native-lottie/src/Lottie.php",
            "vendor/pushinbr/pam-native-lottie/composer.json",
            "vendor/composer/installed.json",
            "vendor/composer/LICENSE",
            "vendor/nikic/php-parser/README.md",
            "src/Screens/README.md",
            "assets/legal/terms.md",
        ] {
            assert!(!is_sdk_host_material(Path::new(path)), "{path}");
        }
    }
}
