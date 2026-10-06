# Native capabilities

Pam Native exposes platform features through typed PHP APIs. Coded variants
are integer-backed enums and protocol additions are append-only.

For copyable end-to-end PHP and tag recipes for every capability in this
table, see the [capability cookbook](examples.md).

| Capability | PHP entry point |
| --- | --- |
| Gestures | `UI\GestureDetector` |
| Video and audio | `UI\MediaPlayer` |
| Voice recording | `System\AudioRecorder` |
| Camera and gallery | `System\MediaCapture`, `System\MediaLibrary`, `System\Files` |
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
| Multipart uploads with progress | `Http\Http::multipart()`, `Http\Http::uploadWithProgress()` |
| Sharing files | `System\Share::files()` |
| Saving to Photos/Gallery | `System\MediaLibrary::save()` |
| Screenshot protection | `System\Screen::secure()`, `Route::screen(...)->secure()` |
| Conversation notifications | `System\Notifications::conversation()`, `Notifications\PushRendering` |
| Screen-reader announcements | `System\Accessibility::announce()` |
| Cancellable timers | `System\Timers::timeout()`, `Timers::every()` |
| App lifecycle | `App::onStateChange()` |

## Web and media

`WebView` accepts a URL or inline HTML, custom user agent, injected JavaScript,
DOM storage and a safe `PamNative.postMessage(value)` bridge. `MediaPlayer`
uses `TextureView`/`MediaPlayer` on Android and `AVPlayerViewController` on iOS,
with controls, autoplay, looping, mute, volume, seek, rate and progress events.
When `thumbnail` is set, Android keeps that poster visible until the first
decoded video frame is rendered, including during remote preparation and
initial buffering.

## Links

Outgoing and incoming link operations accept optional failure callbacks. Use
them for product UI because native failures arrive asynchronously and cannot be
caught around the original method call:

```php
use Pam\Native\System\Linking;

Linking::open(
    'https://example.com',
    static function (): void {
        // The platform accepted the URL.
    },
    static function (string $message): void {
        // Show non-blocking feedback or offer a fallback action.
    },
);
```

`Linking::canOpen()` and `Linking::initial()` expose the same optional failure
callback as their final argument. Omitting it preserves the original exception
behavior for compatibility.

## Voice recording

`AudioRecorder` records AAC/M4A voice media and exposes bounded amplitude
updates. Every asynchronous operation accepts an optional failure callback so
the application can restore its UI when the native recorder is unavailable:

```php
use Pam\Native\AudioRecording;
use Pam\Native\System\AudioRecorder;

AudioRecorder::start(
    static function (): void {
        // Recording started. AudioRecorder::watch() can now drive a waveform.
    },
    static function (string $message): void {
        // Restore the composer and show non-blocking feedback.
    },
);

AudioRecorder::stop(
    static function (AudioRecording $recording): void {
        // Upload $recording->relativePath and preserve $recording->durationMs.
    },
    static function (string $message): void {
        // Restore the composer; the native stop failed.
    },
);
```

Omitting the failure callback preserves the original exception behavior.

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
By default, each file is bounded to 64 MiB and a multi-selection is bounded to
256 MiB. A single document can request a MIME filter and a larger import limit:

```php
Files::pick(
    MediaPickerType::Any,
    function (?FileReference $archive): void { /* use the private file path */ },
    mimeType: 'application/zip',
    failure: function (string $message): void { /* show a recoverable error */ },
    maximumBytes: 2_147_483_648,
);
```

The maximum explicit limit is 8 GiB. Android streams the selected document to
the app sandbox; iOS copies the security-scoped document without loading it
into application memory. Partial files are removed when an import fails.

On Android, `MediaLibrary::assets()` queries the device gallery directly with
bounded offset pagination, an optional album filter and an image/video type
filter. It returns `MediaAssetPage` metadata and stable `content://` thumbnail
sources without copying the files or moving image bytes through PHP.
`MediaLibrary::albums()` returns typed album counts and cover sources. Both
queries run on a dedicated native worker and respect full or user-selected
photo access. Call `Files::importUri()` only after selection to copy that
single asset into the PAM sandbox for editing, upload or durable app-owned
storage.

On iOS, the same calls use PhotoKit. Pages return stable `phasset://asset/`
sources that `Image` and `Video` resolve on demand without copying the library.
`Files::importUri()` exports only the selected asset into the PAM sandbox and
enforces the 64 MiB import limit. PhotoKit does not expose resource byte sizes
in its list metadata, so `MediaAsset::size` is `0` until the asset is imported;
the resulting `FileReference::size` reports the copied file's size. Add
`NSPhotoLibraryUsageDescription` to the iOS host's Info.plist before requesting
photo permission.

`MediaCapture::capture()`
captures a full-resolution photo or video. `Files::read()` and `Files::write()`
only accept sandbox-relative paths and bridge at most one MiB per call; imports
are bounded to 64 MiB. `Files::stat()` returns typed metadata,
`Files::list()` inventories regular files in a sandbox directory, and
`Files::delete()` removes a single sandbox file. Directory deletion and paths
escaping the application sandbox are rejected.

`Files::copyAsset()` copies a project-relative packaged asset directly into a
sandbox-relative destination on the native file worker and returns its typed
`FileReference`. It does not base64-encode the asset or move its bytes through
PHP, so packaged editor templates and other large immutable resources can be
materialized safely. Absolute paths, empty segments and traversal are rejected
for both the asset and destination.

`Files::download()` materializes an absolute HTTPS resource at a
sandbox-relative destination and returns a `FileReference` suitable for image
editing, upload or durable app-owned use. Downloads run off the UI thread,
reject embedded credentials and non-HTTPS URLs, enforce a caller-controlled
size limit (64 MiB by default, 256 MiB maximum), and replace the destination
atomically only after a successful transfer.

`Files::sha256($file->path, $callback, $failure)` computes a lowercase SHA-256
digest on the native file worker. Only the private path and 64-character digest
cross the bridge. Files are read in 64 KiB blocks and limited to 64 MiB; empty
files have the standard SHA-256 empty digest. The optional failure callback
receives native read, path or size errors. Without it, native failure follows
the normal exception behavior. Available since SDK 1.0.20.

Use it before requesting an upload grant that binds the expected content
digest. Keep the source unchanged until the upload completes and require the
server to validate the uploaded bytes: hashing alone does not lock the file
or prevent a concurrent write of the same size. The operation does not copy,
delete or modify the source file.

Use `Files::downloadWithProgress()` for authenticated documents or visible
transfer UI. Request headers are validated on both sides of the bridge;
connection framing headers and CR/LF values are rejected. The progress closure
receives a typed `FileDownloadProgress`, while completion returns the same
`FileReference` as `download()`. Call `Files::cancelDownload()` when the owning
screen is disposed. `Files::open()` opens a compatible platform viewer: Android
grants a temporary read-only content URI; iOS previews the private file with
Quick Look. The sandbox path itself is never exposed to another application.

The system document picker does not require broad storage permission.
Applications that call `MediaLibrary` request `PermissionKind::Photos`;
Android 13+ reports image/video access and Android 14+ selected-photo access
as a typed `PermissionStatus::Limited` decision when appropriate. iOS hosts
must provide the standard camera/photo usage descriptions in the application
`Info.plist`. Direct `MediaLibrary` queries are available on both platforms;
the document picker remains the fallback that does not request broad access.

## Foreground file uploads

`Http::upload()` sends a private `Files` path as the raw body of an HTTPS
`PUT`. Pass `FileReference::$path`, not its `pam-file://` URI or an absolute
device path. Available since SDK 1.0.20.

```php
use Pam\Native\Http\Http;
use Pam\Native\Http\HttpResponse;

Http::upload(
    url: $signedUploadUrl,
    path: $file->path,
    callback: function (HttpResponse $response): void {
        // Check the expected storage status before confirming with your API.
    },
    headers: ['Content-Type' => $file->mimeType],
);
```

Native workers first copy the source into a private temporary snapshot, then
stream that snapshot to the server. File bytes do not pass through PHP or
the bridge. Sources are limited to 64 MiB; applications should enforce any
smaller limit required by their API. The source remains available after the
request, while the snapshot is removed when the request completes or fails.
Snapshot creation requires temporary disk space approximately equal to the
file size. Avoid modifying the source until snapshot creation has completed;
the server should validate a previously agreed checksum when byte identity
matters.

The default network deadline is 120 seconds, configurable from 1 to 120
seconds. Queueing and snapshot creation precede this deadline. Redirects are
returned to the caller without forwarding the upload. Native code supplies
the content length: `Host`, `Content-Length`, `Transfer-Encoding`,
`Connection`, `Trailer`, and `Upgrade` cannot be supplied by the application.
Use only headers required by the upload destination; an API bearer token
should not be copied to an unrelated storage URL.

Pass `progress:` (or call `Http::uploadWithProgress()`, which returns an
`HttpTransfer` handle with `cancel()`) to receive `TransferProgress` events;
the deadline then applies per socket read/write instead of to the whole
request. Without progress, uploads are foreground operations with no automatic
retry, per-request cancellation, or process-restart recovery. Closing the native HTTP
module interrupts active requests. A timeout does not establish whether the
server received the file: query the application's upload status before
retrying or completing a business operation. HTTP response bodies retain the
normal transport limit of 900 KiB.

### Multipart uploads

`Http::multipart()` builds a `multipart/form-data` request whose file parts are
streamed natively from private files with a fixed `Content-Length`:

```php
use Pam\Native\Http\Http;
use Pam\Native\Http\HttpResponse;
use Pam\Native\Http\TransferProgress;

$upload = Http::multipart('https://api.example.com/media')
    ->file('media', $file->path, $file->mimeType)
    ->field('caption', $caption)
    ->bearer($token)
    ->onProgress(fn (TransferProgress $p) => $this->progress->set($p->fraction()))
    ->send(fn (HttpResponse $response) => $this->uploaded($response));

$upload->cancel(); // e.g. when the user removes the attachment
```

Up to 64 parts and 2 GiB per request; fields are limited to 1 MiB. The
builder owns `Content-Type`; transport headers cannot be overridden. Progress
is throttled to whole percents or 256 KiB. A cancelled transfer never invokes
its callback. Transport failures arrive as `HttpResponse::transportFailed()`.
Android streams through `HttpURLConnection`; iOS writes the multipart body to
a private temporary file and uploads it with `URLSession` (byte progress from
`didSendBodyData`, cancellation through the task), deleting the file when the
transfer ends.

`HttpResponse::$headers` (lower-case names), `header($name)` and `date()`
expose response headers for every request, e.g. to correct client clock skew
with the server `Date`.

## Sharing, moving and saving files

```php
Share::files(['exports/photo.jpg', 'exports/clip.mp4'], title: 'Send');
Files::move('drafts/a.jpg', 'sent/a.jpg', fn (FileReference $file) => ...);
Files::copy('sent/a.jpg', 'backup/a.jpg', overwrite: true);
Files::makeDirectory('media/cache');
MediaLibrary::save('exports/photo.jpg', album: 'My App', callback: fn (MediaAsset $asset) => ...);
```

`Share::files()` grants temporary read access through the PAM FileProvider
(`ACTION_SEND` / `ACTION_SEND_MULTIPLE`) or presents `UIActivityViewController`.
The MIME type defaults to the narrowest type shared by every file. Copies are
staged and published atomically; moves never replace a destination unless
`overwrite: true`.

`MediaLibrary::save()` publishes an image or video into
`Pictures/<album>` or `Movies/<album>` through MediaStore on Android 10+
without any permission. Android 8–9 request `WRITE_EXTERNAL_STORAGE`, which
the app must declare in `pam-native.json`; PAM caps it with
`maxSdkVersion="28"`. iOS requests add-only Photos access
(`NSPhotoLibraryAddUsageDescription`).

## Screen privacy and accessibility

```php
Route::screen('wallet', WalletScreen::class)->secure(); // scoped to the route
Screen::secure(true);                                   // explicit app-wide claim
Accessibility::announce('Message sent');
```

Route-scoped secure mode sets `FLAG_SECURE` while the route is the active entry
of its navigator and clears it when the route is popped or replaced. The
effective state is the union of route claims and the explicit claim. iOS
cannot block screenshots (no public `FLAG_SECURE`); instead, while secure mode
is on, PAM covers every window with an opaque privacy shield whenever the
screen is recorded, mirrored or AirPlayed (`UIScreen.isCaptured`) and while the
app is inactive, so the app-switcher snapshot never shows protected content.
The callback receives `true`; the native result also reports
`screenshotsBlocked: false`.

`Accessibility::announce()` sends a polite TalkBack/VoiceOver announcement and
is a no-op when no screen reader runs; `screenReaderEnabled()` reports state.

## Images, timers and lifecycle

`Image::prefetch($urls, MediaPriority::Visible, $headers)` downloads remote
images into the renderer disk cache (`pam-images-v1`) using the same key as
`Image::make()`, so later renders and notification avatars load from disk.
Pass the same headers and `cacheKeys` the `Image` will use. iOS stores the
images in the renderer media cache (`pam-media-v1`) two at a time, higher
priority first.

`Timers::timeout()` and `Timers::every()` return a `TimerHandle` with
`cancel()`; `Timers::cancel($id)` also cancels ids returned by
`Timers::after()`. `App::onStateChange(fn (AppState $state) => ...)` subscribes
any number of listeners to Active/Inactive/Background transitions
(`offStateChange()` unsubscribes, `App::state()` returns the latest).

`ScrollView::make(...)->onEndReached($handler, threshold: 0.5)` (template:
`on:endReached`) fires when the remaining content is within the threshold
fraction of the viewport, and re-arms after the content grows or the user
scrolls back (Android).

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

On iOS, install the official `pam-native-share-extension` package to generate
the Share Extension and shared App Group. Its accepted types come from
`plugins.shareExtension` in `pam-native.json` (see
[Share sheets and per-application configuration](plugins.md#share-sheets-and-per-application-configuration)). The same `IncomingShares` API consumes
one extension delivery at startup and delivers queued items when the app
becomes active. Received files are copied into PAM's private file sandbox
before PHP receives their relative paths. Choose either `IncomingShares` or
the plugin's `ShareInbox::drain()` for a given application; both consume the
same extension inbox.

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
Permission denial reaches the normal callback with `false`. Unexpected native
failures can be handled separately without invoking the global error handler:

```php
Notifications::requestPermission(
    fn (bool $granted) => $this->onPermissionDecision($granted),
    failure: fn (string $message) => $this->onPermissionFailure($message),
);
```

The failure callback is optional; omitting it preserves existing error reporting.
On Android, place the Firebase client file at
`.pam/google-services.json` (preferred) or `google-services.json` in the PAM
project root. The generated host then enables Firebase Messaging,
`PushNotifications::register()` returns its token, and received messages enter
the persistent PAM event stream automatically. Projects without that file do
not compile or package Firebase. iOS hosts forward their app-delegate
callbacks:

Push token acquisition can fail when the provider is unavailable or the client
configuration is rejected. Pass the optional failure callback to recover
without turning an expected asynchronous provider failure into a global runtime
exception:

```php
PushNotifications::register(
    fn ($token) => $this->sendTokenToServer($token),
    fn (string $message) => $this->recordPushRegistrationFailure($message),
);
```

Omitting the callback preserves the legacy exception behavior.

After the server removes the installation token, disable provider delivery and
invalidate the native token with the same failure contract:

```php
PushNotifications::unregister(
    fn () => $this->markNotificationsDisabled(),
    fn (string $message) => $this->recordPushUnregistrationFailure($message),
);
```

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

The framework owns token acquisition and the bounded receive/open event stream.
Queued Android receives survive Activity and PHP-runtime startup. Provider
transport and server-side delivery remain application configuration. See the
[production capability guide](production-capabilities.md) for FCM/APNs setup
and automatic deep-link routing.

### Conversation notifications

```php
Notifications::conversation('chat:42')
    ->title('Weekend trip')->group()
    ->self(Person::make('You'))
    ->message(Person::make('Ana', $avatarUrl, 'user-7'), 'See you there?', $sentAtMs, 'msg-1')
    ->reply('Reply', ActionEndpoint::post('https://api.example.com/chats/42/messages')
        ->bearerFromStorage('auth.token')->json(['body' => '{reply}', 'client_id' => '{uuid}']))
    ->markRead('Mark as read')
    ->deepLink('myapp://chat/42')
    ->show();

Notifications::onAction(function (NotificationAction $action): void {
    if ($action->isReply() && !$action->delivered()) {
        $this->sendMessage($action->conversation, $action->text);
    }
});

Notifications::cancelGroup('chat:42');
```

Android renders `MessagingStyle` with `Person` avatars (HTTPS, private file or
`pam-file:///`), a `RemoteInput` reply action and a mark-as-read action.
Messages merge into a bounded native history (25 messages, deduplicated by id),
so each push adds only the new message. Replies are appended as the user's own
message so Android stops the reply spinner. Actions are persisted in a native
queue and delivered to `Notifications::onAction()` (one listener; a new
listener replaces the previous one), even when they happened while PHP was not
running. An optional `ActionEndpoint` performs the HTTP request natively at tap
time; `NotificationAction::delivered()` reports its 2xx result. Endpoint
templates accept push data fields, `{reply}`, `{conversation}`, `{uuid}`,
`{now}` and `{storage:key}` (a value saved with `Storage`). iOS posts one
threaded communication notification per conversation (latest message, sender
avatar and group name through `INSendMessageIntent` when the app has the
Communication Notifications capability) with a text-input reply action and a
mark-as-read action; `PamPushNotifications.didReceive(response:completionHandler:)`
runs the endpoint natively (inside a background task) and queues the action.

### Declarative push rendering

Rules render data-only Firebase pushes natively while PHP is suspended or the
process was started just for the push. Register them at every boot:

```php
PushRendering::forType('chat.message')
    ->conversation('{chat_id}', title: '{chat_title}', group: '{is_group}')
    ->message(sender: '{sender_name}', text: '{body}', avatar: '{sender_avatar}',
        timestamp: '{sent_at}', id: '{message_id}')
    ->self('You')
    ->reply('Reply', $replyEndpoint)
    ->markRead('Mark as read')
    ->deepLink('myapp://chat/{chat_id}')
    ->channel('messages', 'Messages')
    ->suppressWhenRoute('chat/{chat_id}')          // deep-link path pattern
    ->suppressWhenRoute(AppRoute::Chat, ['chatId' => '{chat_id}'])
    ->register();

PushRendering::forType('chat.read')->dismissConversation('{chat_id}')->register();
PushRendering::forType('promo')->notification('{_title}', '{_body}')->register();
```

A rule matches when `data[$field]` (default `type`) equals the type.
Placeholders read top-level data fields; `{_title}`, `{_body}` and `{_id}`
expose the push envelope; timestamps accept seconds, milliseconds or ISO 8601.
Suppression applies only while the app is in the foreground and the focused
route matches (navigators report focused routes once a rule uses
`suppressWhenRoute`). Rendered pushes still reach `PushNotifications::listen()`
with `PushMessage::$rendered = true`, so PHP must not display them again.
Pushes that carry a `notification` payload are displayed by the OS and bypass
rules; send data-only messages. On iOS send them as background pushes
(`content-available: 1`, no `alert`); the host forwards
`application(_:didReceiveRemoteNotification:fetchCompletionHandler:)` to
`PamPushNotifications.didReceiveRemote(userInfo:completion:)`, which applies the
rules natively. iOS does not deliver background pushes to an app the user
force-quit, and throttles them under Low Power Mode.

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
network state with `NetworkType` (`powerSaveMode()` aliases `lowPowerMode`).
`DeviceInfo` adds `memoryClassMb` (Android heap class; iOS available process
memory), `lowRamDevice`, `powerSaveMode` and `constrained()` to scale caches,
prefetching and animation down on weak devices.

## Permissions

`PermissionKind` adds `BluetoothConnect` (7, Android 12+ `BLUETOOTH_CONNECT`,
iOS Bluetooth), `FullScreenIntent` (8, Android 14+ special access opened in
Settings) and `PhoneState` (9, `READ_PHONE_STATE`). `PermissionStatus::Unavailable`
(5) marks permissions that do not exist on the platform (`FullScreenIntent` and
`PhoneState` on iOS). Declare the Android permissions in `pam-native.json`
`android.permissions`; generation adds only declared permissions.
