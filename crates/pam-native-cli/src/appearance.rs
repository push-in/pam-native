//! `pam-native.json` `appearance` section: per-scheme native window colours
//! applied before the first PHP frame (window background, system bars and the
//! Android 12+ splash screen) and the default light/dark preference.

use std::path::Path;

use serde::Deserialize;

#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum AppearanceMode {
    #[default]
    System,
    Light,
    Dark,
}

impl AppearanceMode {
    /// Integer contract shared with PHP `AppearanceMode` and the hosts.
    pub const fn value(self) -> u8 {
        match self {
            Self::System => 1,
            Self::Light => 2,
            Self::Dark => 3,
        }
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AppearancePalette {
    #[serde(default)]
    pub background: Option<String>,
    #[serde(default)]
    pub status_bar: Option<String>,
    #[serde(default)]
    pub navigation_bar: Option<String>,
    #[serde(default)]
    pub splash_background: Option<String>,
}

/// Android 8–11 draw the starting window from the system scheme before the
/// process starts, so it cannot follow a persisted override. `None` skips it:
/// the launcher stays visible until the correctly themed window is ready.
#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum LegacyStartingWindow {
    #[default]
    Theme,
    None,
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AppearanceOptions {
    #[serde(default)]
    pub default_mode: AppearanceMode,
    #[serde(default)]
    pub legacy_starting_window: LegacyStartingWindow,
    #[serde(default)]
    pub light: AppearancePalette,
    #[serde(default)]
    pub dark: AppearancePalette,
}

const LIGHT_BACKGROUND: u32 = 0xFFFFFF;
const DARK_BACKGROUND: u32 = 0x121212;

/// Fully resolved opaque colours for one scheme.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct ResolvedPalette {
    pub background: u32,
    pub status_bar: u32,
    pub navigation_bar: u32,
    pub splash_background: u32,
}

impl ResolvedPalette {
    fn resolve(palette: &AppearancePalette, fallback: u32, scheme: &str) -> Result<Self, String> {
        let color = |value: &Option<String>, field: &str, default: u32| {
            match value {
            Some(value) => parse_color(value).ok_or_else(|| {
                format!(
                    "appearance.{scheme}.{field} must be an opaque #RGB or #RRGGBB colour, got {value:?}"
                )
            }),
            None => Ok(default),
        }
        };
        let background = color(&palette.background, "background", fallback)?;
        Ok(Self {
            background,
            status_bar: color(&palette.status_bar, "statusBar", background)?,
            navigation_bar: color(&palette.navigation_bar, "navigationBar", background)?,
            splash_background: color(&palette.splash_background, "splashBackground", background)?,
        })
    }
}

impl AppearanceOptions {
    pub fn light_palette(&self) -> Result<ResolvedPalette, String> {
        ResolvedPalette::resolve(&self.light, LIGHT_BACKGROUND, "light")
    }

    pub fn dark_palette(&self) -> Result<ResolvedPalette, String> {
        ResolvedPalette::resolve(&self.dark, DARK_BACKGROUND, "dark")
    }

    pub fn validate(&self) -> Result<(), String> {
        self.light_palette()?;
        self.dark_palette()?;
        Ok(())
    }

    /// Writes the Android `values` and `values-night` resources consumed by
    /// the DayNight theme and `PamAppearance`.
    pub fn write_android_resources(
        &self,
        res: &Path,
        write: impl Fn(&Path, &[u8]) -> Result<(), String>,
    ) -> Result<(), String> {
        write(
            &res.join("values/pam_appearance.xml"),
            android_resources(
                &self.light_palette()?,
                Some((self.default_mode, self.legacy_starting_window)),
            )
            .as_bytes(),
        )?;
        write(
            &res.join("values-night/pam_appearance.xml"),
            android_resources(&self.dark_palette()?, None).as_bytes(),
        )
    }
}

fn parse_color(value: &str) -> Option<u32> {
    let hex = value.strip_prefix('#')?;
    if !hex.chars().all(|character| character.is_ascii_hexdigit()) {
        return None;
    }
    match hex.len() {
        3 => {
            let expanded: String = hex.chars().flat_map(|c| [c, c]).collect();
            u32::from_str_radix(&expanded, 16).ok()
        }
        6 => u32::from_str_radix(hex, 16).ok(),
        _ => None,
    }
}

/// WCAG relative luminance; dark icons are used on surfaces lighter than mid grey.
fn wants_dark_icons(rgb: u32) -> bool {
    let channel = |shift: u32| {
        let value = f64::from((rgb >> shift) & 0xFF) / 255.0;
        if value <= 0.03928 {
            value / 12.92
        } else {
            ((value + 0.055) / 1.055).powf(2.4)
        }
    };
    let luminance = 0.2126 * channel(16) + 0.7152 * channel(8) + 0.0722 * channel(0);
    luminance > 0.179
}

pub fn hex(rgb: u32) -> String {
    format!("#{rgb:06X}")
}

fn android_resources(
    palette: &ResolvedPalette,
    defaults: Option<(AppearanceMode, LegacyStartingWindow)>,
) -> String {
    let mut xml = String::from(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Generated from pam-native.json \"appearance\" by the PAM Native CLI. -->\n<resources>\n",
    );
    if let Some((mode, starting_window)) = defaults {
        xml.push_str(&format!(
            "    <integer name=\"pam_appearance_default_mode\">{}</integer>\n    <bool name=\"pam_disable_starting_window\">{}</bool>\n",
            mode.value(),
            starting_window == LegacyStartingWindow::None,
        ));
    }
    for (name, color) in [
        ("pam_window_background", palette.background),
        ("pam_status_bar", palette.status_bar),
        ("pam_navigation_bar", palette.navigation_bar),
        ("pam_splash_background", palette.splash_background),
    ] {
        xml.push_str(&format!(
            "    <color name=\"{name}\">#FF{color:06X}</color>\n"
        ));
    }
    xml.push_str(&format!(
        "    <bool name=\"pam_light_status_bar\">{}</bool>\n    <bool name=\"pam_light_navigation_bar\">{}</bool>\n</resources>\n",
        wants_dark_icons(palette.status_bar),
        wants_dark_icons(palette.navigation_bar),
    ));
    xml
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::RefCell;
    use std::collections::BTreeMap;
    use std::path::PathBuf;

    #[test]
    fn defaults_match_the_bundled_host_resources() {
        let options = AppearanceOptions::default();
        let files = RefCell::new(BTreeMap::<PathBuf, String>::new());
        options
            .write_android_resources(Path::new("res"), |path, bytes| {
                files.borrow_mut().insert(
                    path.to_path_buf(),
                    String::from_utf8(bytes.to_vec()).expect("utf8"),
                );
                Ok(())
            })
            .expect("resources");
        let files = files.into_inner();
        let host = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../android/app/src/main/res");
        for name in [
            "values/pam_appearance.xml",
            "values-night/pam_appearance.xml",
        ] {
            assert_eq!(
                files[&Path::new("res").join(name)],
                std::fs::read_to_string(host.join(name)).expect("bundled resource"),
                "{name} must equal the CLI defaults"
            );
        }
    }

    #[test]
    fn configured_palettes_generate_day_night_resources_and_icon_contrast() {
        let options: AppearanceOptions = serde_json::from_str(
            r##"{"defaultMode":"dark","legacyStartingWindow":"none","light":{"background":"#F7F6F2"},"dark":{"background":"#111511","navigationBar":"#fff"}}"##,
        )
        .expect("appearance");
        let light = android_resources(
            &options.light_palette().unwrap(),
            Some((options.default_mode, options.legacy_starting_window)),
        );
        let dark = android_resources(&options.dark_palette().unwrap(), None);
        assert!(light.contains("<integer name=\"pam_appearance_default_mode\">3</integer>"));
        assert!(light.contains("<bool name=\"pam_disable_starting_window\">true</bool>"));
        assert!(light.contains("<color name=\"pam_window_background\">#FFF7F6F2</color>"));
        assert!(light.contains("<color name=\"pam_splash_background\">#FFF7F6F2</color>"));
        assert!(light.contains("<bool name=\"pam_light_status_bar\">true</bool>"));
        assert!(dark.contains("<color name=\"pam_window_background\">#FF111511</color>"));
        assert!(dark.contains("<color name=\"pam_navigation_bar\">#FFFFFFFF</color>"));
        assert!(dark.contains("<bool name=\"pam_light_status_bar\">false</bool>"));
        assert!(dark.contains("<bool name=\"pam_light_navigation_bar\">true</bool>"));
        assert!(!dark.contains("pam_appearance_default_mode"));
        assert!(!dark.contains("pam_disable_starting_window"));
    }

    #[test]
    fn invalid_colours_and_unknown_fields_fail_closed() {
        for value in ["F7F6F2", "#F7F6F2AA", "#12", "#GGGGGG", "red"] {
            let options = AppearanceOptions {
                light: AppearancePalette {
                    background: Some(value.to_owned()),
                    ..AppearancePalette::default()
                },
                ..AppearanceOptions::default()
            };
            assert!(
                options
                    .validate()
                    .unwrap_err()
                    .contains("appearance.light.background"),
                "{value} must be rejected"
            );
        }
        assert!(
            serde_json::from_str::<AppearanceOptions>(r##"{"light":{"foreground":"#000"}}"##)
                .is_err()
        );
        assert!(serde_json::from_str::<AppearanceOptions>(r#"{"defaultMode":"dim"}"#).is_err());
    }
}
