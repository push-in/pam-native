# Production capabilities

This guide covers the production layer added on top of the native capability
APIs. Every coded status/type is an integer-backed PHP enum.

The [capability cookbook](examples.md) includes complete permission,
notification, push, observation and cleanup recipes.

## Permissions

Declare every optional Android capability used by the application in
`pam-native.json`. PAM also includes permissions declared by installed native
plugins and removes unused optional permissions from the generated application:

```json
{
  "android": {
    "permissions": [
      "android.permission.CAMERA",
      "android.permission.POST_NOTIFICATIONS"
    ]
  }
}
```

Declaring a permission does not grant it. Request it at the point of use with
the typed API so the user sees the reason in context.

Use `PermissionKind` instead of platform permission strings:

```php
Permissions::status(PermissionKind::Camera, function ($decision): void {
    if (!$decision->granted() && $decision->canAskAgain) {
        Permissions::requestKind(PermissionKind::Camera, fn ($result) => null);
    }
});
```

`PermissionStatus` distinguishes granted, denied, blocked and limited access.
For blocked access, call `Permissions::openSettings()` after explaining why the
application needs it.

iOS hosts add the usage-description keys required by enabled capabilities:
`NSCameraUsageDescription`, `NSMicrophoneUsageDescription`,
`NSPhotoLibraryUsageDescription`, `NSLocationWhenInUseUsageDescription` and
`NSContactsUsageDescription`.

## Contacts

Request the typed permission before reading the address book. Results include
stable platform identifiers, names, phone numbers and email addresses:

```php
Permissions::requestKind(PermissionKind::Contacts, function ($decision): void {
    if (!$decision->granted()) {
        return;
    }

    Contacts::all(function (array $contacts): void {
        foreach ($contacts as $contact) {
            echo $contact->displayName;
        }
    });
});
```

`Contacts::all()` transparently reads bounded pages so a large address book
does not exceed the native bridge payload limit.

## Current location

Request location permission first, then provide both success and failure
callbacks so timeout/provider failures can restore loading state without an
uncaught asynchronous exception:

```php
Location::current(
    callback: function (LocationPosition $position): void {
        // Use $position->latitude, longitude and accuracy.
    },
    highAccuracy: true,
    timeoutMs: 15_000,
    maximumAgeMs: 10_000,
    failure: function (string $failure): void {
        Toast::show(match (LocationError::fromFailure($failure)) {
            LocationError::Permission => 'Allow location access.',
            LocationError::Disabled => 'Turn on location.',
            LocationError::Timeout => 'Location took too long. Try again outdoors.',
            LocationError::Unavailable => 'Location is unavailable right now.',
        });
    },
);
```

The failure callback is optional for backwards compatibility. Without one,
native failures retain the legacy exception behavior.

On Android, `current()`, `watch()` and `lastKnown()` use the Google Play
Services fused provider (`FusedLocationProviderClient`, `PRIORITY_HIGH_ACCURACY`
or `PRIORITY_BALANCED_POWER_ACCURACY` from `highAccuracy`) whenever Google Play
Services is available, like React Native's `locationProvider: 'playServices'`,
and the platform `LocationManager` otherwise. A fix at most `maximumAgeMs` old
(the fused `lastLocation`, or a provider's last known location) answers without
a new request. iOS uses Core Location.

### Failure contract

Every location failure is a string `"<code>: <detail>"`, for example
`"disabled: No enabled location provider"`. The code is stable and identical
on Android and iOS; the detail is English diagnostics, never user copy:

| `LocationError` | Code | When |
| --- | --- | --- |
| `Permission` (1) | `permission` | No location permission (Core Location `.denied`). |
| `Disabled` (2) | `disabled` | The system location switch is off (no enabled provider). |
| `Unavailable` (3) | `unavailable` | No fix, no last known position, or a platform error. |
| `Timeout` (4) | `timeout` | No fix within `timeoutMs`. |

`LocationError::fromFailure(string $failure): LocationError` reads the code
(bare messages from runtimes before 1.35.0 are recognized too) and
`LocationError::detail(string $failure): string` strips it. Show your own
localized message per case.

### Last known position

```php
Location::lastKnown(
    callback: fn (LocationPosition $position) => $this->showApproximate($position),
    failure: fn (string $failure) => null, // LocationError::Unavailable when there is none
);
```

`lastKnown()` never turns on GPS: it returns the fused `lastLocation`, then
the newest position any `LocationManager` provider still holds (iOS:
`CLLocationManager.location`).

### Location services (the system switch)

The permission and the system location switch are separate. When a request
fails with `LocationError::Disabled`, ask to turn location on:

```php
Location::servicesEnabled(function (bool $enabled): void {
    if ($enabled) {
        return;
    }
    Location::requestServices(function (LocationServicesResult $result): void {
        match ($result) {
            LocationServicesResult::Enabled => $this->locate(),
            LocationServicesResult::Denied => null, // the user said no: do not ask again now
            LocationServicesResult::Unavailable => Location::openSettings(),
        };
    });
});
```

- `Location::servicesEnabled(Closure(bool): void $callback)`: Android
  `LocationManagerCompat.isLocationEnabled`, iOS
  `CLLocationManager.locationServicesEnabled()`.
- `Location::requestServices(Closure(LocationServicesResult): void $callback)`:
  Android shows Google Play Services' "turn on location" dialog
  (`SettingsClient.checkLocationSettings` + the resolution activity) and
  resolves `Enabled` (1) or `Denied` (2). Without Google Play Services or an
  activity no dialog is possible: `Enabled` when the switch is already on,
  otherwise `Unavailable` (3). iOS has no such dialog: `Enabled` or
  `Unavailable`.
- `Location::openSettings(?Closure(bool): void $completed = null)`: Android
  `Settings.ACTION_LOCATION_SOURCE_SETTINGS`; iOS the app's Settings page
  (`UIApplication.openSettingsURLString`, the only one apps may open).
  `$completed` receives whether the screen opened.

## Watching the position

`Location::watch()` is React Native's `watchPosition`: the platform location
service (Android fused `requestLocationUpdates`, or `LocationManager` without
Google Play Services; iOS `CLLocationManager.startUpdatingLocation`) wakes the
app only after the device moved `distanceFilterMeters` (`0` = every update), at
most every `intervalMs` on Android. Keep it to the foreground (the app declares
no background location) and clear it when the feature ends:

```php
$watch = Location::watch(
    callback: function (LocationPosition $position): void {
        // Throttle and send the fix.
    },
    highAccuracy: true,
    distanceFilterMeters: 20.0,
    intervalMs: 5_000,
    failure: function (string $failure): void {
        // LocationError::Permission or LocationError::Disabled.
    },
);
// ... on background, logout or when sharing ends:
Location::clearWatch($watch);
```

## Push delivery, opening and deep links

```php
$registration = PushNotifications::register(
    $sendTokenToServer,
    $recordProviderFailure,
);
$subscription = PushNotifications::listenAndRoute($navigator, $onMessage);

// After the application server forgets this installation:
PushNotifications::unregister($onRemoved, $recordProviderFailure);
```

The second `register()` callback receives provider/configuration failures and
keeps them in application control. It is optional; omitting it preserves the
legacy exception behavior.

`unregister()` invalidates the FCM token on Android and unregisters the
application from APNs on iOS. Remove the installation token from the
application server first so a provider failure can be retried safely.

Android projects only need their Firebase client file at
`.pam/google-services.json` (preferred) or root `google-services.json`. PAM
conditionally compiles the Firebase service and dependency, synchronizes the
client file through a cache-safe incremental Gradle task after every generated
host refresh, forwards notification and data payloads to `PushNotifications`,
and persists up to 64 unconsumed events across process startup. Pam
notification-opening intents are forwarded automatically. Custom native
integrations may still call `PamPushNotifications.reportReceived(...)` or
`reportOpened(...)`.

Debug builds must use a package ID separate from production. Without Firebase,
PAM appends `.debug` automatically. When a Firebase client file is present,
set `android.debugApplicationIdSuffix` in `pam-native.json`, for example
`.pamqa`. PAM refuses a debug build that would reuse the production package ID
and `pam doctor` reports the same issue. The suffix applies to launch, logs and
diagnostics; release builds keep `applicationId`. The Google services file must
contain an Android client for the suffixed debug package.
If that client does not exist, set `"debugFirebase": false` under `android`
instead: debug builds then omit Firebase Messaging, use the `.debug` suffix
(push registration reports an actionable failure) and release builds keep
Firebase.

`pam-native build --benchmark` produces the installable release-optimized
variant (R8, non-debuggable, baseline profile, signed with the local debug key)
in `dist/<name>-<version>-android-benchmark.apk`; `run --release`, `benchmark`
and `profile` use the same variant. Set `android.benchmarkApplicationIdSuffix`
(for example `.perf`) to install it beside production for cold-start and
profiling measurements; such builds omit Firebase Messaging.

iOS notification delegates forward foreground delivery with
`PamPushNotifications.didReceive(notification:)` and opening with
`PamPushNotifications.didOpen(response:)`.

Queued delivery uses a bounded 64-event buffer and 256 KiB data payload.
`listenAndRoute()` only opens deep links for numeric event `Opened = 2`.

## Continuous observation

```php
$sensor = Sensors::watch(SensorType::Accelerometer, $onReading, 50);
$device = DeviceStatus::watch($onDeviceStatus, 1_000);

Sensors::unwatch($sensor);
DeviceStatus::unwatch($device);
```

The bridge is pull-driven with a four-value native queue. If PHP is busy, old
samples are discarded in favor of recent state instead of growing memory.

## Lifecycle and recovery

Android pauses WebView timers and active media from `Activity.onPause()` and
resumes only media that was playing. iOS observes active/inactive notifications
and applies the same player behavior.

Components implementing `Restorable` persist on lifecycle transitions.
`Navigator`, `TabNavigator` and `DrawerNavigator` restore their stacks,
selected destinations and parameters. Picker/camera operations cancelled by
process death should be restarted from restored product state.

## DevTools

The overlay contains a bounded capability timeline with module latency,
failure state, semantic events, lifecycle changes and runtime errors. On iOS,
pass `onDiagnostic: overlay.record` to `PamRuntime`.

The panel uses system monospaced text, accessible high contrast, screen-reader
output and no decorative motion.

## Security limits

- `WebView::allowedHosts()` applies an exact main-frame host allowlist.
- WebView roots reject executable/custom schemes; inline HTML remains explicit.
- WebView main-frame navigation is cancelled after a 30-second timeout.
- File paths stay canonicalized inside `pam-files`.
- Imports stop while streaming at 64 MiB; bridge reads/writes remain 1 MiB.
- SQLite queries stop at 1,000 rows or 256 columns; paginate larger results.
- Push queues, identifiers, text, deep links and JSON data have bounded sizes.
- Runtime event payloads remain bounded to one MiB.
