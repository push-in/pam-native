# Brownfield adoption

PAM Native can enter an existing application one surface at a time. The host
owns authentication, navigation, release cadence, and native code; PAM owns
only the runtime surface that the host explicitly opens.

## iOS: contained view controller

`PamNativeViewController` is a normal UIKit child. It accepts a local PHP
entrypoint plus optional native modules/views, starts only after its view loads,
tracks bounds, Dynamic Type and dark appearance, and closes deterministically.

```swift
let pam = PamNativeViewController(
    entryURL: bundleURL.appendingPathComponent("index.php"),
    nativeModules: hostModules,
    nativeViews: hostViews,
    onError: { logger.error("PAM: \($0)") }
)
navigationController.pushViewController(pam, animated: true)
```

The controller can also be added with the standard
`addChild`/`didMove(toParent:)` containment API. No PAM backend is required.

## Android: incremental full-screen entry

The small public AAR exposes `PamNativeLauncher`. It uses an explicit intent and
first verifies that the host packaged the PAM runtime activity:

```kotlin
if (PamNativeLauncher.isAvailable(context)) {
    PamNativeLauncher.launch(context)
}
```

This is the stable Android 1.x full-screen brownfield boundary. It does not
pretend to be an inline `View`; inline Android containment remains excluded
until the complete renderer/JNI host is shipped as a self-contained AAR with
resource, lifecycle, result, permission, and back-dispatch isolation.

Both paths preserve package independence: the existing host may use any server,
or none, and PAM Native does not install PAM HTTP or a backend adapter.
