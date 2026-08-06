# Production capabilities

This guide covers the production layer added on top of the native capability
APIs. Every coded status/type is an integer-backed PHP enum.

The [capability cookbook](examples.md) includes complete permission,
notification, push, observation and cleanup recipes.

## Permissions

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
    failure: function (string $message): void {
        Toast::show($message !== '' ? $message : 'Location is unavailable.');
    },
);
```

The failure callback is optional for backwards compatibility. Without one,
native failures retain the legacy exception behavior.

## Push delivery, opening and deep links

```php
$subscription = PushNotifications::listenAndRoute($navigator, $onMessage);
```

Android projects only need their Firebase client file at
`.pam/google-services.json` (preferred) or root `google-services.json`. PAM
conditionally compiles the Firebase service and dependency, synchronizes the
client file through a cache-safe incremental Gradle task after every generated
host refresh, forwards notification and data payloads to `PushNotifications`,
and persists up to 64 unconsumed events across process startup. Pam
notification-opening intents are forwarded automatically. Custom native
integrations may still call `PamPushNotifications.reportReceived(...)` or
`reportOpened(...)`.

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
