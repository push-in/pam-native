# Native capabilities

Pam Native exposes platform features through typed PHP APIs. Coded variants
are integer-backed enums and protocol additions are append-only.

For copyable end-to-end PHP and tag recipes for every capability in this
table, see the [capability cookbook](examples.md).

| Capability | PHP entry point |
| --- | --- |
| Gestures | `UI\GestureDetector` |
| Video and audio | `UI\MediaPlayer` |
| Camera and gallery | `System\MediaCapture`, `System\Files::pick()`, `System\Files::pickMany()` |
| Gesture navigation | `Navigation\Navigator`, `NavigationHost` |
| Bottom Sheet | `UI\BottomSheet` |
| Declarative animations | `UI\Animated` |
| WebView | `UI\WebView` |
| Files and documents | `System\Files` |
| Finite background work | `System\BackgroundTasks` |
| Local and push notifications | `System\Notifications`, `System\PushNotifications` |
| Incoming and outgoing links | `System\Linking` |
| Content shared from other apps | `System\IncomingShares` |
| Cache usage and cleanup | `System\Caches` |
| SQLite | `Database\SQLite` |
| Advanced images | `UI\Image` |
| Clipboard, drag/drop and menus | `System\Clipboard`, `UI\InteractionRegion` |
| Sensors and device state | `System\Sensors`, `System\DeviceStatus` |

## Web and media

`WebView` accepts a URL or inline HTML, custom user agent, injected JavaScript,
DOM storage and a safe `PamNative.postMessage(value)` bridge. `MediaPlayer`
uses `VideoView`/`MediaPlayer` on Android and `AVPlayerViewController` on iOS,
with controls, autoplay, looping, mute, volume, seek, rate and progress events.

## Files, camera and gallery

`Files::pick()` imports an image, video, audio file or document into the
application sandbox and returns a `FileReference`. `Files::pickMany()` uses the
native multi-selection picker, preserves selection order, imports off the UI
thread, and returns up to 50 typed `FileReference` values in one bridge result.
`FileReference::uri()` returns a sandboxed `pam-file:///...` source that can be
passed directly to `Image` and rendered immediately without copying bytes
through PHP or exposing an absolute device path. Image reads and decoding stay
off the UI thread, and both renderers reject authority and path traversal.
If any import fails, files already copied by that selection are removed.
Each file is bounded to 64 MiB and a multi-selection is bounded to 256 MiB.
`MediaCapture::capture()`
captures a full-resolution photo or video. `Files::read()` and `Files::write()`
only accept sandbox-relative paths and bridge at most one MiB per call; imports
are bounded to 64 MiB. `Files::stat()` returns typed metadata,
`Files::list()` inventories regular files in a sandbox directory, and
`Files::delete()` removes a single sandbox file. Directory deletion and paths
escaping the application sandbox are rejected.

Android declares no broad storage permission and uses the system document
provider. iOS hosts must provide the standard camera/photo usage descriptions
in the application `Info.plist`.

## Incoming shares

Declare only the MIME types the application accepts:

```json
{
  "android": {
    "shareTargets": ["image/*", "video/*", "text/plain"]
  }
}
```

`IncomingShares::initial()` consumes a cold-start share and
`IncomingShares::listen()` receives warm-start `ACTION_SEND` and
`ACTION_SEND_MULTIPLE` intents. Android copies every received file into the
private application cache before notifying PHP. The resulting
`IncomingShare::$files` are ordinary `FileReference` values and remain usable
after the source activity and its temporary URI grant are gone. Incoming
events are bounded to 10 files, 16 queued shares and 64 KiB of text.

```php
use Pam\Native\IncomingShare;
use Pam\Native\System\IncomingShares;

$openComposer = static function (IncomingShare $share): void {
    // $share->text, $share->subject and $share->files
};

IncomingShares::initial(static function (?IncomingShare $share) use ($openComposer): void {
    if ($share !== null) {
        $openComposer($share);
    }
});
IncomingShares::listen($openComposer);
```

## Background, notifications and push

`BackgroundTasks::begin()` grants a bounded finite background execution window
(partial wake lock on Android, `UIBackgroundTask` on iOS); always call
`BackgroundTasks::end()`.

`Notifications` requests permission and schedules/cancels local notifications.
`PushNotifications::register()` returns an FCM token when the Android host
includes Firebase Messaging. iOS hosts forward their app-delegate callbacks:

```swift
func application(
    _ application: UIApplication,
    didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data
) {
    PamPushNotifications.didRegister(deviceToken: deviceToken)
}

func application(
    _ application: UIApplication,
    didFailToRegisterForRemoteNotificationsWithError error: Error
) {
    PamPushNotifications.didFailToRegister(error: error)
}
```

The framework owns token acquisition and the bounded receive/open event stream;
provider transport and server-side delivery remain host configuration. See the
[production capability guide](production-capabilities.md) for FCM/APNs delegate
forwarding and automatic deep-link routing.

## SQLite

`Database\SQLite` opens databases under the private application directory.
Statements execute on a serial native queue and positional values are bound,
not interpolated. Query rows return typed JSON scalars. Databases use WAL with
`synchronous=NORMAL` and a bounded busy timeout. `SQLite::executeMany()` reuses
one prepared statement inside one native transaction for high-volume writes.

## Interaction and motion

`InteractionRegion` provides system drag-and-drop and context menus.
`Animated` runs declarative keyframes for opacity, translation, scale and
rotation without driving frames through PHP. Both respect cancellation and the
platform reduced-motion setting.

`Navigator` enables leading-edge interactive pop gestures by default, including
RTL direction, velocity completion and cancellation.

## Device state and sensors

`Sensors::read()` and `Sensors::watch()` support accelerometer (1), gyroscope
(2), magnetometer (3) and device motion/attitude (4).
`System\DeviceStatus::read()`/`watch()` return battery, charging, low-power and
network state with `NetworkType`.
