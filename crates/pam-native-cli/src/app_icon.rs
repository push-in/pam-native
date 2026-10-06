//! Launcher/home-screen icon: `android.icon` (adaptive icon) and `ios.icon`
//! (1024 pt App Store icon). Without them the hosts keep the bundled PAM icon.
//!
//! On Android 12+ the system splash screen draws the launcher icon unless
//! `appearance.splash` provides its own, so an app that ports a React Native
//! (or any native) project gets the very same splash by reusing its adaptive
//! icon layers here.

use std::fs;
use std::path::Path;

use serde::Deserialize;

/// `android.icon`: the layers of an adaptive icon (API 26+).
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AndroidIcon {
    /// Project-relative PNG/WebP foreground on the 108 dp adaptive canvas
    /// (432 px or larger keeps xxxhdpi sharp).
    pub foreground: String,
    /// `#RRGGBB` colour or a project-relative PNG/WebP layer (default white).
    #[serde(default)]
    pub background: Option<String>,
    /// Optional single-colour layer for Android 13+ themed icons.
    #[serde(default)]
    pub monochrome: Option<String>,
}

const GENERATED: &str =
    "<!-- Generated from pam-native.json \"android.icon\" by the PAM Native CLI. -->";

/// Generated resource files; every one is removed again when `android.icon`
/// is dropped so the bundled icon comes back.
const ANDROID_LAYERS: [&str; 3] = [
    "pam_launcher_foreground",
    "pam_launcher_background",
    "pam_launcher_monochrome",
];
const ANDROID_ADAPTIVE: &str = "drawable-anydpi-v26/pam_launcher.xml";
const ANDROID_COLOR: &str = "values/pam_launcher.xml";

impl AndroidIcon {
    pub fn validate(&self) -> Result<(), String> {
        image_extension(&self.foreground).ok_or_else(|| {
            format!(
                "android.icon.foreground must be a project-relative .png or .webp path, got {:?}",
                self.foreground
            )
        })?;
        if let Some(background) = &self.background {
            if parse_color(background).is_none() && image_extension(background).is_none() {
                return Err(format!(
                    "android.icon.background must be #RGB/#RRGGBB or a project-relative .png/.webp path, got {background:?}"
                ));
            }
        }
        if let Some(monochrome) = &self.monochrome {
            image_extension(monochrome).ok_or_else(|| {
                format!(
                    "android.icon.monochrome must be a project-relative .png or .webp path, got {monochrome:?}"
                )
            })?;
        }
        Ok(())
    }
}

/// Writes (or removes) the adaptive launcher icon resources under `res`.
/// The manifest always points at `@drawable/pam_launcher`; the bundled
/// `drawable/pam_launcher.xml` is the PAM icon and the generated
/// `drawable-anydpi-v26` variant replaces it on every supported release.
pub fn sync_android_icon(
    icon: Option<&AndroidIcon>,
    project_root: &Path,
    res: &Path,
    write: impl Fn(&Path, &[u8]) -> Result<(), String>,
) -> Result<(), String> {
    for layer in ANDROID_LAYERS {
        for extension in ["png", "webp"] {
            remove_if_present(&res.join(format!("drawable-nodpi/{layer}.{extension}")))?;
        }
    }
    remove_if_present(&res.join(ANDROID_ADAPTIVE))?;
    remove_if_present(&res.join(ANDROID_COLOR))?;
    let Some(icon) = icon else {
        return Ok(());
    };
    icon.validate()?;
    let copy_layer = |source: &str, layer: &str| -> Result<(), String> {
        let extension = image_extension(source)
            .ok_or_else(|| format!("Invalid android.icon image path {source}"))?;
        let bytes = fs::read(project_root.join(source))
            .map_err(|error| format!("Cannot read android.icon image {source}: {error}"))?;
        write(
            &res.join(format!("drawable-nodpi/{layer}.{extension}")),
            &bytes,
        )
    };
    copy_layer(&icon.foreground, "pam_launcher_foreground")?;
    let background = match icon.background.as_deref() {
        Some(value) if image_extension(value).is_some() => {
            copy_layer(value, "pam_launcher_background")?;
            "@drawable/pam_launcher_background".to_owned()
        }
        value => {
            let color = value.and_then(parse_color).unwrap_or(0xFFFFFF);
            write(
                &res.join(ANDROID_COLOR),
                format!(
                    "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n{GENERATED}\n<resources>\n    <color name=\"pam_launcher_background\">#FF{color:06X}</color>\n</resources>\n"
                )
                .as_bytes(),
            )?;
            "@color/pam_launcher_background".to_owned()
        }
    };
    let monochrome = match icon.monochrome.as_deref() {
        Some(source) => {
            copy_layer(source, "pam_launcher_monochrome")?;
            "\n    <monochrome android:drawable=\"@drawable/pam_launcher_monochrome\" />"
        }
        None => "",
    };
    write(
        &res.join(ANDROID_ADAPTIVE),
        format!(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n{GENERATED}\n<adaptive-icon xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <background android:drawable=\"{background}\" />\n    <foreground android:drawable=\"@drawable/pam_launcher_foreground\" />{monochrome}\n</adaptive-icon>\n"
        )
        .as_bytes(),
    )
}

/// Writes (or removes) `AppIcon.appiconset` in the host asset catalog.
/// Returns whether the app icon is configured, so the project can name it in
/// `ASSETCATALOG_COMPILER_APPICON_NAME`.
pub fn sync_ios_icon(
    icon: Option<&str>,
    project_root: &Path,
    catalog: &Path,
    write: impl Fn(&Path, &[u8]) -> Result<(), String>,
) -> Result<bool, String> {
    let icon_set = catalog.join("AppIcon.appiconset");
    if icon_set.exists() {
        fs::remove_dir_all(&icon_set)
            .map_err(|error| format!("Cannot remove {}: {error}", icon_set.display()))?;
    }
    let Some(source) = icon else {
        return Ok(false);
    };
    let bytes = read_ios_icon(source, project_root)?;
    fs::create_dir_all(&icon_set)
        .map_err(|error| format!("Cannot create {}: {error}", icon_set.display()))?;
    write(&icon_set.join("AppIcon-1024.png"), &bytes)?;
    write(
        &icon_set.join("Contents.json"),
        b"{\n  \"images\" : [\n    {\n      \"filename\" : \"AppIcon-1024.png\",\n      \"idiom\" : \"universal\",\n      \"platform\" : \"ios\",\n      \"size\" : \"1024x1024\"\n    }\n  ],\n  \"info\" : {\n    \"author\" : \"xcode\",\n    \"version\" : 1\n  }\n}\n",
    )?;
    Ok(true)
}

/// `ios.icon` must be a 1024×1024 PNG (Xcode derives every other size).
pub fn read_ios_icon(source: &str, project_root: &Path) -> Result<Vec<u8>, String> {
    if image_extension(source) != Some("png") {
        return Err(format!(
            "ios.icon must be a project-relative .png path, got {source:?}"
        ));
    }
    let bytes = fs::read(project_root.join(source))
        .map_err(|error| format!("Cannot read ios.icon {source}: {error}"))?;
    match png_size(&bytes) {
        Some((1024, 1024)) => Ok(bytes),
        Some((width, height)) => Err(format!(
            "ios.icon must be 1024x1024 pixels, {source} is {width}x{height}"
        )),
        None => Err(format!("ios.icon {source} is not a PNG file")),
    }
}

fn png_size(png: &[u8]) -> Option<(u32, u32)> {
    if png.len() < 24 || !png.starts_with(b"\x89PNG\r\n\x1a\n") || &png[12..16] != b"IHDR" {
        return None;
    }
    Some((
        u32::from_be_bytes([png[16], png[17], png[18], png[19]]),
        u32::from_be_bytes([png[20], png[21], png[22], png[23]]),
    ))
}

fn remove_if_present(path: &Path) -> Result<(), String> {
    if path.is_file() {
        fs::remove_file(path)
            .map_err(|error| format!("Cannot remove {}: {error}", path.display()))?;
    }
    Ok(())
}

fn image_extension(path: &str) -> Option<&'static str> {
    crate::appearance::splash_extension(path)
}

fn parse_color(value: &str) -> Option<u32> {
    crate::appearance::parse_color(value)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::collections::BTreeMap;
    use std::path::PathBuf;

    fn temp_dir(name: &str) -> PathBuf {
        let dir = std::env::temp_dir().join(format!("pam-app-icon-{name}-{}", std::process::id()));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn png(width: u32, height: u32) -> Vec<u8> {
        let mut bytes = b"\x89PNG\r\n\x1a\n\0\0\0\x0dIHDR".to_vec();
        bytes.extend_from_slice(&width.to_be_bytes());
        bytes.extend_from_slice(&height.to_be_bytes());
        bytes.extend_from_slice(&[8, 6, 0, 0, 0]);
        bytes
    }

    #[test]
    fn android_icon_writes_adaptive_layers_and_cleans_up() {
        let root = temp_dir("android");
        fs::create_dir_all(root.join("assets")).unwrap();
        fs::write(root.join("assets/fg.webp"), b"fg").unwrap();
        fs::write(root.join("assets/mono.png"), b"mono").unwrap();
        let res = root.join("res");
        let written = RefCell::new(BTreeMap::new());
        let write = |path: &Path, bytes: &[u8]| -> Result<(), String> {
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, bytes).unwrap();
            written.borrow_mut().insert(
                path.strip_prefix(&res)
                    .unwrap()
                    .to_string_lossy()
                    .into_owned(),
                bytes.to_vec(),
            );
            Ok(())
        };
        let icon = AndroidIcon {
            foreground: "assets/fg.webp".into(),
            background: Some("#000".into()),
            monochrome: Some("assets/mono.png".into()),
        };
        sync_android_icon(Some(&icon), &root, &res, write).unwrap();
        let files = written.borrow();
        let adaptive = String::from_utf8(files[ANDROID_ADAPTIVE].clone()).unwrap();
        assert!(
            adaptive.contains("<background android:drawable=\"@color/pam_launcher_background\" />")
        );
        assert!(adaptive.contains("@drawable/pam_launcher_foreground"));
        assert!(
            adaptive
                .contains("<monochrome android:drawable=\"@drawable/pam_launcher_monochrome\" />")
        );
        assert!(
            String::from_utf8(files[ANDROID_COLOR].clone())
                .unwrap()
                .contains("#FF000000")
        );
        assert_eq!(files["drawable-nodpi/pam_launcher_foreground.webp"], b"fg");
        assert_eq!(files["drawable-nodpi/pam_launcher_monochrome.png"], b"mono");
        drop(files);

        sync_android_icon(None, &root, &res, |_: &Path, _: &[u8]| Ok(())).unwrap();
        assert!(!res.join(ANDROID_ADAPTIVE).exists());
        assert!(!res.join(ANDROID_COLOR).exists());
        assert!(
            !res.join("drawable-nodpi/pam_launcher_foreground.webp")
                .exists()
        );
        assert!(
            !res.join("drawable-nodpi/pam_launcher_monochrome.png")
                .exists()
        );
        fs::remove_dir_all(root).unwrap();
    }

    #[test]
    fn android_icon_rejects_invalid_layers() {
        for icon in [
            AndroidIcon {
                foreground: "../fg.png".into(),
                background: None,
                monochrome: None,
            },
            AndroidIcon {
                foreground: "fg.svg".into(),
                background: None,
                monochrome: None,
            },
            AndroidIcon {
                foreground: "fg.png".into(),
                background: Some("black".into()),
                monochrome: None,
            },
            AndroidIcon {
                foreground: "fg.png".into(),
                background: None,
                monochrome: Some("m.jpg".into()),
            },
        ] {
            assert!(icon.validate().is_err(), "{icon:?}");
        }
        assert_eq!(parse_color("#0a0"), Some(0x00AA00));
        assert_eq!(parse_color("#123456"), Some(0x123456));
    }

    #[test]
    fn ios_icon_requires_a_1024_png() {
        let root = temp_dir("ios");
        fs::write(root.join("icon.png"), png(1024, 1024)).unwrap();
        fs::write(root.join("small.png"), png(512, 512)).unwrap();
        let catalog = root.join("App/PamLaunch.xcassets");
        let write = |path: &Path, bytes: &[u8]| -> Result<(), String> {
            fs::create_dir_all(path.parent().unwrap()).unwrap();
            fs::write(path, bytes).map_err(|error| error.to_string())
        };
        assert!(sync_ios_icon(Some("icon.png"), &root, &catalog, write).unwrap());
        let contents =
            fs::read_to_string(catalog.join("AppIcon.appiconset/Contents.json")).unwrap();
        assert!(contents.contains("\"size\" : \"1024x1024\""));
        assert!(
            catalog
                .join("AppIcon.appiconset/AppIcon-1024.png")
                .is_file()
        );
        assert!(
            read_ios_icon("small.png", &root)
                .unwrap_err()
                .contains("1024x1024")
        );
        assert!(read_ios_icon("icon.webp", &root).is_err());
        assert!(!sync_ios_icon(None, &root, &catalog, write).unwrap());
        assert!(!catalog.join("AppIcon.appiconset").exists());
        fs::remove_dir_all(root).unwrap();
    }
}
