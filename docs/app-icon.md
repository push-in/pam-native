# App icon

`pam-native.json` declares the home-screen icon of each platform. Without it
the hosts keep the bundled PAM icon.

```json
{
    "android": {
        "icon": {
            "foreground": "assets/icon/android-foreground.webp",
            "background": "#000000",
            "monochrome": "assets/icon/android-monochrome.webp"
        }
    },
    "ios": {
        "icon": "assets/icon/ios-1024.png"
    }
}
```

## Android

`android.icon` describes an adaptive icon (API 26+, every release PAM Native
supports):

| Field | Purpose |
| --- | --- |
| `foreground` | Project-relative PNG/WebP on the 108 dp adaptive canvas (keep the artwork inside the central 66 dp safe zone). 432 px or larger stays sharp at xxxhdpi. |
| `background` | `#RGB`/`#RRGGBB` colour or a project-relative PNG/WebP layer. Default `#FFFFFF`. |
| `monochrome` | Optional single-colour layer for Android 13+ themed icons. |

The build copies the layers to `res/drawable-nodpi/pam_launcher_*` and writes
`res/drawable-anydpi-v26/pam_launcher.xml`; the manifest's `android:icon` is
`@drawable/pam_launcher`. Removing `android.icon` deletes the generated files
and the PAM icon comes back. Porting a React Native app: use its
`mipmap-xxxhdpi/ic_launcher_foreground` (and `_background`, `_monochrome`)
images, or the colour of `ic_launcher_background`.

Android 12+ also draws the launcher icon on the system splash screen unless
`appearance.splash` sets its own icon, see
[Appearance](appearance.md#keeping-the-launcher-icon-on-android-12-1210).

Notification small icons keep `@drawable/pam_icon`: Android requires an
alpha-only glyph there, which an adaptive icon is not.

## iOS

`ios.icon` is a project-relative 1024×1024 PNG (opaque, no rounded corners;
iOS applies the mask). The build writes it as `AppIcon.appiconset` in
`App/PamLaunch.xcassets` and sets `ASSETCATALOG_COMPILER_APPICON_NAME = AppIcon`;
Xcode derives every other size. Without `ios.icon` neither is generated.
