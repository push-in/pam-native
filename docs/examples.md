# Capability cookbook

These recipes complement the focused guides and show the shortest production
shape for the native APIs.

## WebView and native media

```php
use Pam\Native\UI\WebView;

$browser = WebView::make('https://app.example.com')
    ->allowedHosts(['app.example.com', 'cdn.example.com'])
    ->javaScriptEnabled()
    ->domStorageEnabled()
    ->injectedJavaScript('window.PamNative.postMessage("ready")')
    ->onMessage(fn (string $message) => $this->handleWebMessage($message))
    ->onError(fn (string $message) => $this->report($message));
```

```html
<MediaPlayer
    source="https://cdn.example.com/intro.mp4"
    cache="disk"
    cache-key="intro:v4"
    streaming-cache
    download-while-playing
    controls
/>
```

See [native media cache](media-cache.md) for policies, TTL, checksums, offline
pinning and cache events.

## Files, document picker and camera

```php
use Pam\Native\CaptureType;
use Pam\Native\FileReference;
use Pam\Native\FileDownloadProgress;
use Pam\Native\MediaPickerType;
use Pam\Native\PermissionKind;
use Pam\Native\PermissionStatus;
use Pam\Native\System\Files;
use Pam\Native\System\MediaCapture;
use Pam\Native\System\MediaLibrary;
use Pam\Native\System\Permissions;

Files::write('drafts/message.txt', 'Hello', function (): void {
    Files::read('drafts/message.txt', fn (string $text) => $this->show($text));
});

Files::copyAsset(
    'assets/templates/story.webp',
    'drafts/story.webp',
    fn (FileReference $file) => $this->storySource = $file->uri(),
);

Files::download(
    'https://cdn.example.com/posts/42.webp',
    'drafts/shared-post.webp',
    fn (FileReference $file) => $this->sharedPostSource = $file->uri(),
    maximumBytes: 16 * 1_024 * 1_024,
);

$this->reportDownload = Files::downloadWithProgress(
    'https://api.example.com/reports/42.pdf',
    'documents/report-42.pdf',
    function (FileReference $file): void {
        Files::open(
            $file->path,
            $file->mimeType,
            static function (): void {},
            fn (string $message) => $this->show($message),
        );
    },
    fn (FileDownloadProgress $progress) => $this->progress = $progress->fraction(),
    headers: ['Authorization' => 'Bearer '.$this->token],
    failure: fn (string $message) => $this->show($message),
);

// When leaving a screen with an active transfer:
Files::cancelDownload($this->reportDownload);

Files::pick(MediaPickerType::Image, function ($file): void {
    $this->selectedPath = $file->path;
    $this->selectedImageSource = $file->uri();
});

Files::pickMany(
    MediaPickerType::Image,
    function (array $files): void {
        foreach ($files as $file) {
            // Upload $file->path without moving bytes through PHP.
        }
    },
    limit: 10,
);

// Android custom gallery: query metadata first, import only the chosen asset.
Permissions::requestKind(PermissionKind::Photos, function ($decision): void {
    if (!in_array(
        $decision->status,
        [PermissionStatus::Granted, PermissionStatus::Limited],
        true,
    )) {
        return;
    }

    MediaLibrary::assets(
        MediaPickerType::Media,
        function ($page): void {
            $this->gallery = $page->items;
            $this->hasMore = $page->hasMore;
        },
        limit: 80,
        offset: 0,
    );
});

MediaLibrary::albums(
    MediaPickerType::Media,
    fn (array $albums) => $this->albums = $albums,
);

Files::importUri(
    'content://media/external/images/media/42',
    function ($file): void {
        // $file->path is now app-owned and ready for edit/upload.
        $this->selectedImageSource = $file->uri();
    },
);

MediaCapture::capture(CaptureType::Photo, function ($photo): void {
    $this->photoPath = $photo->path;
});

Files::list('drafts', fn (array $files) => $this->drafts = $files);
Files::stat('drafts/message.txt', fn ($file) => $this->draftSize = $file->size);
Files::delete('drafts/message.txt');
```

Returned paths are sandbox-relative `FileReference` values. Keep larger media
on the native file path instead of reading it through the PHP bridge.
`MediaLibrary` is Android-only in this release; keep `Files::pick()` or
`Files::pickMany()` as the portable and permission-free picker fallback.

## SQLite with bound parameters

```php
use Pam\Native\Database\SQLite;

SQLite::execute(
    'app.db',
    'CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, body TEXT NOT NULL)',
);

SQLite::execute(
    'app.db',
    'INSERT INTO notes (body) VALUES (?)',
    ['Buy coffee'],
);

SQLite::query(
    'app.db',
    'SELECT id, body FROM notes WHERE id > ? ORDER BY id DESC LIMIT ?',
    [0, 50],
    fn (array $rows) => $this->notes = $rows,
);

SQLite::executeMany(
    'app.db',
    'INSERT INTO notes (id, body) VALUES (?, ?)',
    [
        [1, 'One bridge call'],
        [2, 'One prepared statement'],
        [3, 'One native transaction'],
    ],
);
```

Never interpolate user input into SQL; positional arguments are bound natively.
Use `executeMany()` for cache hydration, synchronization, and bulk writes.

## Permissions, local notifications and push

```php
use Pam\Native\NotificationImportance;
use Pam\Native\PermissionKind;
use Pam\Native\System\Notifications;
use Pam\Native\System\Permissions;
use Pam\Native\System\PushNotifications;

Permissions::requestKind(PermissionKind::Notifications, function ($decision): void {
    if (!$decision->granted()) {
        return;
    }

    Notifications::schedule(
        id: 'daily-summary',
        title: 'Your summary is ready',
        body: 'Tap to open it.',
        delaySeconds: 60,
        importance: NotificationImportance::Default,
        data: ['screen' => 'summary'],
        deepLink: '/summary',
    );
});

PushNotifications::register(
    fn ($token) => $this->sendTokenToServer($token),
    fn (string $message) => $this->recordPushRegistrationFailure($message),
);
$pushSubscription = PushNotifications::listenAndRoute(
    $this->navigator,
    fn ($message) => $this->onPushMessage($message),
);

// During component cleanup:
PushNotifications::unsubscribe($pushSubscription);
```

Android FCM is enabled by adding `.pam/google-services.json`; APNs delegate
forwarding and the full provider setup are documented in
[production capabilities](production-capabilities.md).

## Finite background work

```php
use Pam\Native\System\BackgroundTasks;

BackgroundTasks::begin('sync-outbox', 30, function (int $token): void {
    $this->syncOutbox(function () use ($token): void {
        BackgroundTasks::end($token);
    });
});
```

Always end the token on success, failure and cancellation. This API is a bounded
execution window, not an unlimited background service.

## Clipboard

```php
use Pam\Native\System\Clipboard;

Clipboard::setText('Invite code: PAM42');
Clipboard::hasText(function (bool $available): void {
    if ($available) {
        Clipboard::getText(fn (string $text) => $this->paste($text));
    }
});
```

## Sensors and device status

```php
use Pam\Native\SensorType;
use Pam\Native\System\DeviceStatus;
use Pam\Native\System\Sensors;

Sensors::read(
    SensorType::Accelerometer,
    fn ($reading) => $this->initialAcceleration = $reading,
);

$sensorSubscription = Sensors::watch(
    SensorType::DeviceMotion,
    fn ($reading) => $this->attitude = $reading,
    intervalMs: 50,
);
$deviceSubscription = DeviceStatus::watch(
    fn ($status) => $this->deviceStatus = $status,
    intervalMs: 1_000,
);

// During component cleanup:
Sensors::unwatch($sensorSubscription);
DeviceStatus::unwatch($deviceSubscription);
```

## Drag, drop and native context menu

```php
use Pam\Native\NativeMenuItem;
use Pam\Native\UI\InteractionRegion;
use Pam\Native\UI\Text;

$card = InteractionRegion::make(Text::make('Project PAM'))
    ->draggable('project:42')
    ->acceptsDrop()
    ->contextMenu([
        new NativeMenuItem('open', 'Open'),
        new NativeMenuItem('delete', 'Delete', destructive: true),
    ])
    ->onDrop(fn (string $data) => $this->moveItem($data))
    ->onMenuAction(fn (string $id) => $this->runMenuAction($id));
```

## Declarative native animation

```php
use Pam\Native\AnimationEasing;
use Pam\Native\AnimationFillMode;
use Pam\Native\AnimationKeyframe;
use Pam\Native\UI\Animated;
use Pam\Native\UI\Text;

$entrance = Animated::make(
    Text::make('Loaded'),
    [
        new AnimationKeyframe(0.0, opacity: 0, translationY: 16, scaleX: 0.96, scaleY: 0.96),
        new AnimationKeyframe(1.0, opacity: 1, translationY: 0, scaleX: 1, scaleY: 1),
    ],
    durationMs: 260,
    easing: AnimationEasing::EaseOut,
)
    ->fillMode(AnimationFillMode::Forwards)
    ->onComplete(fn () => $this->entranceFinished = true);
```

The platform reduced-motion preference is honored and animation frames do not
cross the PHP/native boundary.

## Gestures and Bottom Sheet

```html
<GestureDetector
    gestureType="pan"
    gestureDirection="horizontal"
    on:gestureUpdate="dragCard"
>
    <View class="card">...</View>
</GestureDetector>

<BottomSheet
    :snapPoints="[0.25, 0.5, 0.9]"
    index="1"
    keyboardBehavior="interactive"
    on:sheetChange="sheetChanged"
    on:sheetDismiss="sheetDismissed"
>
    <View>...</View>
</BottomSheet>
```

See [gestures](gestures.md) and [Bottom Sheet](bottom-sheet.md) for fluent PHP
examples, gesture composition and event payloads.

## Component state and global store

```php
final class Counter extends Component
{
    protected function initialState(): array
    {
        return ['count' => 0];
    }

    public function render(): Renderable
    {
        return Button::make("Count: {$this->state->count}")
            ->onPress(fn () => $this->state->count++);
    }
}
```

For typed props, effects, watchers, error boundaries, context, slots and refs,
see [component runtime](component-runtime.md). For transactions, persistence,
encrypted state, sync, selectors and undo/redo, see [Pam Store](store.md).

## Scheduler and profiling

```php
use Pam\Native\Scheduling\Scheduler;
use Pam\Native\Scheduling\TaskPriority;

$task = Scheduler::schedule(
    fn ($token) => $this->prefetch($token),
    TaskPriority::Background,
    coalesce: 'home.prefetch',
);

// Obsolete work can be cancelled before it enters the frame budget.
$task->token->cancel();
```

Compiler modes, performance gates, safe mode and profiler spans are documented
in [runtime performance](runtime-performance.md).
