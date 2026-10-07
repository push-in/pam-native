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

/// `appearance.firstFrame`: what the window shows first. `Php` (default)
/// holds the launch screen until PHP's first frame is committed; `Window`
/// draws the themed window at once and lets PHP's first frame replace it,
/// for apps whose first PHP frame paints only the background anyway.
#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum FirstFrame {
    #[default]
    Php,
    Window,
}

impl FirstFrame {
    pub fn waits_for_php(self) -> bool {
        self == Self::Php
    }
}

/// Icon of the Android 12+ system splash screen when `appearance.splash.logo`
/// is set: the logo itself, or the launcher icon (`android.icon`) like an app
/// whose theme sets no `windowSplashScreenAnimatedIcon` (React Native,
/// Expo). The logo then only paints the API 26–30 starting window.
#[derive(Clone, Copy, Debug, Default, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum SplashAndroid12Icon {
    #[default]
    Logo,
    AppIcon,
}

/// `appearance.splash`: a centered logo on the Android 12+ splash screen and
/// on the legacy (API 26–30) starting window, per light/dark scheme.
#[derive(Clone, Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct SplashOptions {
    /// Project-relative PNG/WebP used in the light scheme (and dark when no
    /// `darkLogo` is set).
    #[serde(default)]
    pub logo: Option<String>,
    #[serde(default)]
    pub dark_logo: Option<String>,
    /// Logo box edge in dp (24–240; Android 12 masks icons to a 160 dp
    /// circle). Up to 288 when `android12Icon` is `appIcon`, since only the
    /// legacy starting window draws the logo then.
    #[serde(default = "default_splash_size")]
    pub size: u32,
    #[serde(default, rename = "android12Icon")]
    pub android12_icon: SplashAndroid12Icon,
}

const fn default_splash_size() -> u32 {
    96
}

impl Default for SplashOptions {
    fn default() -> Self {
        Self {
            logo: None,
            dark_logo: None,
            size: default_splash_size(),
            android12_icon: SplashAndroid12Icon::Logo,
        }
    }
}

impl SplashOptions {
    fn validate(&self) -> Result<(), String> {
        let maximum = match self.android12_icon {
            SplashAndroid12Icon::Logo => 240,
            SplashAndroid12Icon::AppIcon => 288,
        };
        if !(24..=maximum).contains(&self.size) {
            return Err(format!(
                "appearance.splash.size must be between 24 and {maximum} dp"
            ));
        }
        if self.dark_logo.is_some() && self.logo.is_none() {
            return Err("appearance.splash.darkLogo requires appearance.splash.logo".into());
        }
        for (field, value) in [("logo", &self.logo), ("darkLogo", &self.dark_logo)] {
            if let Some(path) = value {
                splash_extension(path).ok_or_else(|| {
                    format!("appearance.splash.{field} must be a project-relative .png or .webp path, got {path:?}")
                })?;
            }
        }
        if let (Some(light), Some(dark)) = (&self.logo, &self.dark_logo)
            && splash_extension(light) != splash_extension(dark)
        {
            return Err(
                "appearance.splash.logo and darkLogo must use the same image format".into(),
            );
        }
        Ok(())
    }
}

/// Lowercase image extension of a safe project-relative splash path.
pub fn splash_extension(path: &str) -> Option<&'static str> {
    let relative = Path::new(path);
    if path.is_empty()
        || relative.is_absolute()
        || relative
            .components()
            .any(|component| !matches!(component, std::path::Component::Normal(_)))
    {
        return None;
    }
    let lower = path.to_ascii_lowercase();
    if lower.ends_with(".png") {
        Some("png")
    } else if lower.ends_with(".webp") {
        Some("webp")
    } else {
        None
    }
}

#[derive(Clone, Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct AppearanceOptions {
    #[serde(default)]
    pub splash: SplashOptions,
    #[serde(default)]
    pub default_mode: AppearanceMode,
    #[serde(default)]
    pub legacy_starting_window: LegacyStartingWindow,
    #[serde(default)]
    pub first_frame: FirstFrame,
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
        self.splash.validate()
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
        )?;
        for (name, contents) in splash_resources(&self.splash) {
            write(&res.join(name), contents.as_bytes())?;
        }
        write(
            &res.join("values/pam_launch.xml"),
            launch_resources(self.first_frame).as_bytes(),
        )?;
        Ok(())
    }
}

/// `res/values/pam_launch.xml` (bundled default: wait for PHP's first frame).
fn launch_resources(first_frame: FirstFrame) -> String {
    format!(
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<!-- Generated from pam-native.json \"appearance.firstFrame\" by the PAM Native CLI. -->\n<resources>\n    <bool name=\"pam_first_frame_waits_for_php\">{}</bool>\n</resources>\n",
        first_frame.waits_for_php()
    )
}

const GENERATED: &str =
    "<!-- Generated from pam-native.json \"appearance.splash\" by the PAM Native CLI. -->";

/// Theme/drawable resources for the splash logo. Without a logo the bundled
/// defaults are restored (plain window colour, platform default icon).
fn splash_resources(splash: &SplashOptions) -> Vec<(&'static str, String)> {
    let size = splash.size;
    let header = format!("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n{GENERATED}\n");
    let Some(_) = splash.logo else {
        return vec![
            (
                "values/pam_splash.xml",
                format!(
                    "{header}<resources>\n    <style name=\"Theme.PamNative.Splash\" parent=\"Theme.PamNative.Base\">\n        <item name=\"android:windowBackground\">@color/pam_window_background</item>\n    </style>\n</resources>\n"
                ),
            ),
            (
                "values-v31/pam_splash.xml",
                format!(
                    "{header}<resources>\n    <style name=\"Theme.PamNative.Splash\" parent=\"Theme.PamNative.Base\">\n        <item name=\"android:windowBackground\">@color/pam_window_background</item>\n    </style>\n</resources>\n"
                ),
            ),
            (
                "drawable/pam_window_background.xml",
                format!(
                    "{header}<color xmlns:android=\"http://schemas.android.com/apk/res/android\"\n    android:color=\"@color/pam_window_background\" />\n"
                ),
            ),
            (
                "drawable/pam_splash_icon.xml",
                format!(
                    "{header}<shape xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <solid android:color=\"@android:color/transparent\" />\n    <size android:width=\"240dp\" android:height=\"240dp\" />\n</shape>\n"
                ),
            ),
        ];
    };
    vec![
        (
            "values/pam_splash.xml",
            format!(
                "{header}<resources>\n    <style name=\"Theme.PamNative.Splash\" parent=\"Theme.PamNative.Base\">\n        <item name=\"android:windowBackground\">@drawable/pam_window_background</item>\n    </style>\n</resources>\n"
            ),
        ),
        (
            "values-v31/pam_splash.xml",
            match splash.android12_icon {
                SplashAndroid12Icon::Logo => format!(
                    "{header}<resources>\n    <style name=\"Theme.PamNative.Splash\" parent=\"Theme.PamNative.Base\">\n        <item name=\"android:windowBackground\">@color/pam_window_background</item>\n        <item name=\"android:windowSplashScreenAnimatedIcon\">@drawable/pam_splash_icon</item>\n    </style>\n</resources>\n"
                ),
                // The system splash keeps drawing the launcher icon.
                SplashAndroid12Icon::AppIcon => format!(
                    "{header}<resources>\n    <style name=\"Theme.PamNative.Splash\" parent=\"Theme.PamNative.Base\">\n        <item name=\"android:windowBackground\">@color/pam_window_background</item>\n    </style>\n</resources>\n"
                ),
            },
        ),
        (
            "drawable/pam_window_background.xml",
            format!(
                "{header}<layer-list xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <item android:drawable=\"@color/pam_splash_background\" />\n    <item\n        android:width=\"{size}dp\"\n        android:height=\"{size}dp\"\n        android:gravity=\"center\"\n        android:drawable=\"@drawable/pam_splash_logo\" />\n</layer-list>\n"
            ),
        ),
        (
            "drawable/pam_splash_icon.xml",
            format!(
                "{header}<layer-list xmlns:android=\"http://schemas.android.com/apk/res/android\">\n    <item>\n        <shape>\n            <solid android:color=\"@android:color/transparent\" />\n            <size android:width=\"240dp\" android:height=\"240dp\" />\n        </shape>\n    </item>\n    <item\n        android:width=\"{size}dp\"\n        android:height=\"{size}dp\"\n        android:gravity=\"center\"\n        android:drawable=\"@drawable/pam_splash_logo\" />\n</layer-list>\n"
            ),
        ),
    ]
}

pub(crate) fn parse_color(value: &str) -> Option<u32> {
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
            "values/pam_splash.xml",
            "values-v31/pam_splash.xml",
            "drawable/pam_window_background.xml",
            "drawable/pam_splash_icon.xml",
            "values/pam_launch.xml",
        ] {
            assert_eq!(
                files[&Path::new("res").join(name)],
                std::fs::read_to_string(host.join(name)).expect("bundled resource"),
                "{name} must equal the CLI defaults"
            );
        }
    }

    #[test]
    fn first_frame_window_draws_without_waiting_for_php() {
        let options: AppearanceOptions =
            serde_json::from_str(r##"{"firstFrame":"window"}"##).expect("appearance");
        assert!(!options.first_frame.waits_for_php());
        assert!(launch_resources(options.first_frame)
            .contains("<bool name=\"pam_first_frame_waits_for_php\">false</bool>"));
        assert!(AppearanceOptions::default().first_frame.waits_for_php());
        assert!(serde_json::from_str::<AppearanceOptions>(r##"{"firstFrame":"never"}"##).is_err());
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
    fn splash_logo_generates_android_12_and_legacy_resources() {
        let options: AppearanceOptions = serde_json::from_str(
            r#"{"splash":{"logo":"assets/logos/ze.png","darkLogo":"assets/logos/ze-dark.png","size":120}}"#,
        )
        .expect("appearance");
        options.validate().expect("valid splash");
        let files = splash_resources(&options.splash);
        let get = |name: &str| {
            files
                .iter()
                .find(|(path, _)| *path == name)
                .unwrap()
                .1
                .clone()
        };
        assert!(get("values-v31/pam_splash.xml").contains("windowSplashScreenAnimatedIcon"));
        assert!(get("drawable/pam_window_background.xml").contains("android:width=\"120dp\""));
        assert!(get("drawable/pam_splash_icon.xml").contains("@drawable/pam_splash_logo"));
        for invalid in [
            r#"{"splash":{"logo":"../x.png"}}"#,
            r#"{"splash":{"logo":"x.svg"}}"#,
            r#"{"splash":{"logo":"x.png","size":8}}"#,
            r#"{"splash":{"darkLogo":"x.png"}}"#,
            r#"{"splash":{"logo":"x.png","darkLogo":"y.webp"}}"#,
            r#"{"splash":{"logo":"x.png","size":288}}"#,
            r#"{"splash":{"logo":"x.png","size":289,"android12Icon":"appIcon"}}"#,
            r#"{"splash":{"logo":"x.png","android12Icon":"launcher"}}"#,
        ] {
            if let Ok(options) = serde_json::from_str::<AppearanceOptions>(invalid) {
                assert!(options.validate().is_err(), "{invalid} must be rejected");
            }
        }
    }

    #[test]
    fn splash_app_icon_keeps_the_launcher_icon_on_android_12() {
        let options: AppearanceOptions = serde_json::from_str(
            r#"{"splash":{"logo":"assets/splash.webp","size":288,"android12Icon":"appIcon"}}"#,
        )
        .expect("appearance");
        options.validate().expect("valid splash");
        let files = splash_resources(&options.splash);
        let get = |name: &str| {
            files
                .iter()
                .find(|(path, _)| *path == name)
                .unwrap()
                .1
                .clone()
        };
        assert!(!get("values-v31/pam_splash.xml").contains("windowSplashScreenAnimatedIcon"));
        assert!(get("values/pam_splash.xml").contains("@drawable/pam_window_background"));
        assert!(get("drawable/pam_window_background.xml").contains("android:width=\"288dp\""));
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
