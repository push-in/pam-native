# Android 7.x (minSdk 24)

PAM Native apps run on Android 8.0+ (API 26) by default. An app that must keep
Android 7.0/7.1 devices (for example one that replaces a React Native release
with `minSdk` 24 on Google Play) opts in:

```json
{
    "android": {
        "minSdk": 24
    }
}
```

`android.minSdk` accepts 24 to `targetSdk`. Every plugin the app uses must
declare `android.minSdk` 24 or omit it (plugins default to 24); the build
rejects a plugin whose `minSdk` is above the app's.

## What changes below API 26

The renderer is lint-clean (`NewApi`/`InlinedApi` as errors) at minSdk 24 and
keeps the API 26+ path unchanged on newer devices. On Android 7.x:

| Area | Behaviour on API 24/25 |
| --- | --- |
| PHP runtime | `mblen()` and `nl_langinfo()` (bionic API 26) come from `pam_bionic_compat.c`, which is linked only when `minSdk` < 26 and mirrors bionic. |
| Launcher icon | `android.icon` keeps the adaptive icon under `drawable-anydpi-v26` and adds a square `layer-list` fallback. |
| Fonts | Packaged fonts load with `Typeface.createFromAsset`/`createFromFile`; no variable `wght` axis, bold/italic are synthesized. |
| Images | No hardware bitmaps (they start at API 28 anyway); opaque JPEGs decode as `RGB_565`. |
| Files | Atomic moves use `rename(2)` through `android.system.Os`; Base64 uses `android.util.Base64`. |
| Text | No `text-align: justify` (start alignment); `adjustsFontSizeToFit` needs an AppCompat view, so plain text keeps its size. |
| Inputs | No autofill hints or importance (Android 8 feature). |
| Accessibility | Hint text is not exposed as `AccessibilityNodeInfo.hintText`. |
| System bars | No light navigation bar icons. |
| Video | `seekTo` is not frame-exact (`SEEK_CLOSEST` is API 26). |
| Network watch | The default-network callback hops to the main thread itself. |

Test an API 24 build on an Android 7.0 image (`system-images;android-24;google_apis;x86_64`).
