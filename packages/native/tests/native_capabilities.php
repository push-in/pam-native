<?php

declare(strict_types=1);

use Pam\Native\DeviceStatus as DeviceStatusValue;
use Pam\Native\Http\Http;
use Pam\Native\Http\HttpResponse;
use Pam\Native\Http\HttpTransfer;
use Pam\Native\Http\TransferProgress;
use Pam\Native\ImagePrefetchResult;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\MediaAsset;
use Pam\Native\MediaPickerType;
use Pam\Native\MediaPriority;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Navigation\DeepLink;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\NetworkType;
use Pam\Native\Notifications\ActionEndpoint;
use Pam\Native\Notifications\NotificationAction;
use Pam\Native\Notifications\NotificationActionType;
use Pam\Native\Notifications\NotificationCredentials;
use Pam\Native\Notifications\Person;
use Pam\Native\Notifications\PushRendering;
use Pam\Native\Notifications\ReplyFailures;
use Pam\Native\NotificationImportance;
use Pam\Native\FileReference;
use Pam\Native\PermissionDecision;
use Pam\Native\PermissionKind;
use Pam\Native\PermissionStatus;
use Pam\Native\System\Accessibility;
use Pam\Native\System\DeviceInfo;
use Pam\Native\System\Files;
use Pam\Native\System\MediaLibrary;
use Pam\Native\System\Notifications;
use Pam\Native\System\Permissions;
use Pam\Native\System\Screen as SecureScreen;
use Pam\Native\System\Share;
use Pam\Native\UI\Image;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

$lastCall = static function (): array {
    $call = TestDiagnostics::$moduleCall;
    if ($call === null) {
        throw new RuntimeException('No native module call was recorded.');
    }

    return $call + ['values' => $call['payload'] === '' ? [] : Wire::decodeMap($call['payload'])];
};
$rejects = static function (Closure $operation): bool {
    try {
        $operation();
    } catch (InvalidArgumentException|RuntimeException|LogicException) {
        return true;
    }

    return false;
};

// 1. Multipart uploads with progress and cancellation.
$progress = [];
$response = null;
$transfer = Http::multipart('https://api.example.test/media')
    ->file('media', 'uploads/photo.jpg', 'image/jpeg')
    ->field('caption', 'Olá')
    ->fields(['draft' => true, 'order' => 2])
    ->header('X-Client', 'pam')
    ->bearer('secret')
    ->timeout(90_000)
    ->onProgress(static function (TransferProgress $value) use (&$progress): void { $progress[] = $value; })
    ->send(static function (HttpResponse $value) use (&$response): void { $response = $value; });
$start = $lastCall();
$parts = json_decode((string) $start['values']['parts'], true);
$assert(
    $transfer instanceof HttpTransfer
        && $start['module'] === 'http' && $start['method'] === 'transferStart'
        && $start['values']['kind'] === 1 && $start['values']['method'] === 'POST'
        && $start['values']['timeoutMs'] === 90_000
        && json_decode((string) $start['values']['headers'], true) === ['X-Client' => 'pam', 'Authorization' => 'Bearer secret']
        && $parts[0] === ['type' => 2, 'name' => 'media', 'path' => 'uploads/photo.jpg', 'mimeType' => 'image/jpeg', 'filename' => 'photo.jpg']
        && $parts[1] === ['type' => 1, 'name' => 'caption', 'value' => 'Olá']
        && $parts[2]['value'] === '1' && $parts[3]['value'] === '2',
    'Multipart uploads must bridge typed parts, headers and file paths without file bytes.',
);
Runtime::dispatchModuleResult($start['requestId'], ModuleResultStatus::Success->value, Wire::map(['transfer' => 9]));
$next = $lastCall();
$assert($next['method'] === 'transferNext' && $next['values'] == ['transfer' => 9], 'Multipart transfers must observe the native channel.');
Runtime::dispatchModuleResult($next['requestId'], ModuleResultStatus::Success->value, Wire::map(['state' => 1, 'bytesSent' => 25, 'totalBytes' => 100]));
$assert(
    count($progress) === 1 && $progress[0]->fraction() === 0.25 && $progress[0]->percent() === 25 && !$progress[0]->complete()
        && $transfer->active(),
    'Multipart progress must be typed and keep the transfer active.',
);
$next = $lastCall();
Runtime::dispatchModuleResult($next['requestId'], ModuleResultStatus::Success->value, Wire::map(['state' => 2, 'statusCode' => 201, 'body' => '{"id":1}']));
$assert(
    $response instanceof HttpResponse && $response->successful() && $response->body === '{"id":1}' && !$transfer->active()
        && $lastCall()['method'] === 'transferCancel',
    'Completed transfers must deliver the HTTP response and release the native channel.',
);

$cancelledResponse = null;
$cancelled = Http::multipart('https://api.example.test/media')
    ->field('a', 'b')
    ->send(static function (HttpResponse $value) use (&$cancelledResponse): void { $cancelledResponse = $value; });
$cancelStart = $lastCall();
Runtime::dispatchModuleResult($cancelStart['requestId'], ModuleResultStatus::Success->value, Wire::map(['transfer' => 10]));
$pendingNext = $lastCall();
$cancelled->cancel();
$assert($lastCall()['method'] === 'transferCancel' && $lastCall()['values'] == ['transfer' => 10], 'Cancelling must stop the native transfer.');
Runtime::dispatchModuleResult($pendingNext['requestId'], ModuleResultStatus::Failure->value, 'Observation stopped');
$assert($cancelledResponse === null, 'Cancelled transfers must not invoke their callback.');

$failedResponse = null;
Http::multipart('https://api.example.test/media')->field('a', 'b')
    ->send(static function (HttpResponse $value) use (&$failedResponse): void { $failedResponse = $value; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['transfer' => 11]));
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['state' => 3, 'message' => 'offline']));
$assert($failedResponse instanceof HttpResponse && $failedResponse->transportFailed() && $failedResponse->error === 'offline', 'Failed transfers must surface a transport error.');

$assert(
    $rejects(static fn () => Http::multipart('ftp://example.test'))
        && $rejects(static fn () => Http::multipart('https://example.test')->file('f', '../secret'))
        && $rejects(static fn () => Http::multipart('https://example.test')->header('Content-Type', 'text/plain'))
        && $rejects(static fn () => Http::multipart('https://example.test')->header('Content-Length', '1'))
        && $rejects(static fn () => Http::multipart('https://example.test')->field("bad\nname", 'x'))
        && $rejects(static fn () => Http::multipart('https://example.test')->method('GET'))
        && $rejects(static fn () => Http::multipart('https://example.test')->send(static fn () => null)),
    'Multipart uploads must reject unsafe URLs, paths, headers and empty bodies before the bridge.',
);

$uploadProgress = null;
$uploadId = Http::upload(
    'https://storage.example.test/object',
    'videos/clip.mp4',
    static fn (HttpResponse $_): null => null,
    ['Content-Type' => 'video/mp4'],
    progress: static function (TransferProgress $value) use (&$uploadProgress): void { $uploadProgress = $value; },
);
$uploadStart = $lastCall();
$assert(
    $uploadId > 0 && $uploadStart['method'] === 'transferStart' && $uploadStart['values']['kind'] === 2
        && $uploadStart['values']['method'] === 'PUT' && $uploadStart['values']['path'] === 'videos/clip.mp4',
    'Http::upload with progress must stream through the transfer channel.',
);
Runtime::dispatchModuleResult($uploadStart['requestId'], ModuleResultStatus::Success->value, Wire::map(['transfer' => 12]));
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['state' => 1, 'bytesSent' => 10, 'totalBytes' => 10]));
$assert($uploadProgress instanceof TransferProgress && $uploadProgress->complete(), 'File uploads must report byte progress.');
Http::cancelTransfer($uploadId);
Http::upload('https://storage.example.test/object', 'videos/clip.mp4', static fn (HttpResponse $_): null => null);
$assert($lastCall()['method'] === 'upload', 'Http::upload without progress must keep the original snapshot upload.');

// 2. Sharing files.
$shared = false;
Share::files(['exports/a.jpg', 'exports/b.mp4'], title: 'Enviar', opened: static function () use (&$shared): void { $shared = true; });
$share = $lastCall();
$assert(
    $share['module'] === 'files' && $share['method'] === 'share'
        && json_decode((string) $share['values']['paths'], true) === ['exports/a.jpg', 'exports/b.mp4']
        && $share['values']['mimeType'] === '' && $share['values']['title'] === 'Enviar'
        && $share['values']['text'] === '',
    'Share::files must bridge private paths to the native share sheet.',
);
Share::files(['exports/card.jpg'], 'image/jpeg', text: 'Veja https://example.com');
$assert($lastCall()['values']['text'] === 'Veja https://example.com', 'Share::files must bridge the optional caption beside the files.');
Runtime::dispatchModuleResult($share['requestId'], ModuleResultStatus::Success->value, '');
$assert($shared, 'Share::files must confirm the opened share sheet.');
$assert(
    $rejects(static fn () => Share::files([]))
        && $rejects(static fn () => Share::files(['/etc/passwd']))
        && $rejects(static fn () => Share::files(['a.txt'], 'not a mime')),
    'Share::files must validate paths and MIME types.',
);

// Optional direct Android targets preserve sandbox validation and report capability on iOS.
Share::fileToPackage('exports/card.jpg', 'com.instagram.android', 'image/jpeg', 'caption');
$assert($lastCall()['values']['packageName'] === 'com.instagram.android' && $lastCall()['values']['text'] === 'caption', 'Targeted shares must preserve caption and explicit package.');
$installed = null;
Share::isPackageInstalled('com.whatsapp', static function (?bool $value) use (&$installed): void { $installed = $value; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['supported' => true, 'installed' => true]));
$assert($installed === true, 'Installed Android package must be reported.');
Share::isPackageInstalled('com.whatsapp', static function (?bool $value) use (&$installed): void { $installed = $value; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['supported' => false]));
$assert($installed === null, 'Unsupported package lookup must remain unknown on iOS.');
$shareFailure = '';
Share::fileToPackage('exports/card.jpg', 'com.missing.app', failure: static function (string $error) use (&$shareFailure): void { $shareFailure = $error; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Failure->value, 'missing target');
$assert($shareFailure === 'missing target', 'Missing target must call failure, never silently open another app.');
$assert($rejects(static fn () => Share::fileToPackage('../secret', 'com.instagram.android')) && $rejects(static fn () => Share::fileToPackage('card.jpg', 'bad;package')), 'Targeted sharing must reject escaping paths and malformed packages.');

// 3. File move, copy and directories.
$moved = null;
Files::move('drafts/a.jpg', 'sent/a.jpg', static function (FileReference $file) use (&$moved): void { $moved = $file; }, overwrite: true);
$move = $lastCall();
$assert(
    $move['method'] === 'move' && $move['values'] == ['from' => 'drafts/a.jpg', 'to' => 'sent/a.jpg', 'overwrite' => true],
    'Files::move must bridge both private paths and the overwrite policy.',
);
Runtime::dispatchModuleResult($move['requestId'], ModuleResultStatus::Success->value, Wire::map(['path' => 'sent/a.jpg', 'name' => 'a.jpg', 'mimeType' => 'image/jpeg', 'size' => 3]));
$assert($moved instanceof FileReference && $moved->path === 'sent/a.jpg', 'Files::move must resolve the destination file.');
$copyFailure = null;
Files::copy('a.txt', 'b.txt', failure: static function (string $message) use (&$copyFailure): void { $copyFailure = $message; });
$assert($lastCall()['method'] === 'copy' && $lastCall()['values']['overwrite'] === false, 'Files::copy must not overwrite by default.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Failure->value, 'Destination already exists');
$assert($copyFailure === 'Destination already exists', 'Files::copy must expose native failures.');
$directory = null;
Files::makeDirectory('media/cache', static function (string $path) use (&$directory): void { $directory = $path; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['path' => 'media/cache']));
$assert($directory === 'media/cache', 'Files::makeDirectory must return the normalized path.');
$assert(
    $rejects(static fn () => Files::move('../a', 'b'))
        && $rejects(static fn () => Files::copy('a', '/b'))
        && $rejects(static fn () => Files::makeDirectory('a//b')),
    'File operations must stay inside the private sandbox.',
);

// 5. Secure screens, explicit and route-scoped.
$secureSupported = null;
SecureScreen::secure(true, static function (bool $supported) use (&$secureSupported): void { $secureSupported = $supported; });
$secure = $lastCall();
$assert($secure['module'] === 'window' && $secure['method'] === 'secure' && $secure['values'] == ['enabled' => true], 'Screen::secure must toggle FLAG_SECURE natively.');
Runtime::dispatchModuleResult($secure['requestId'], ModuleResultStatus::Success->value, Wire::map(['enabled' => true, 'supported' => true]));
$assert($secureSupported === true && SecureScreen::isSecure(), 'Screen::secure must report platform enforcement.');
SecureScreen::secure(false);
$assert($lastCall()['values'] == ['enabled' => false] && !SecureScreen::isSecure(), 'Screen::secure(false) must clear the explicit claim.');

$secureNavigator = new Navigator(
    initialRoute: 'inbox',
    routes: [
        'inbox' => static fn () => Screen::make(Text::make('Inbox')),
        'wallet' => static fn () => Screen::make(Text::make('Wallet')),
    ],
    persistenceKey: 'secure-test',
    screenOptions: ['wallet' => new ScreenOptions(secure: true)],
);
TestDiagnostics::$moduleCall = null;
$secureNavigator->render();
$assert(TestDiagnostics::$moduleCall === null, 'Non-secure routes must not call the window module.');
$secureNavigator->push('wallet');
$secureNavigator->render();
$assert($lastCall()['method'] === 'secure' && $lastCall()['values'] == ['enabled' => true] && SecureScreen::isSecure(), 'Secure routes must enable FLAG_SECURE when focused.');
$secureNavigator->pop();
$secureNavigator->render();
$assert($lastCall()['values'] == ['enabled' => false] && !SecureScreen::isSecure(), 'Popping a secure route must clear FLAG_SECURE.');

// 6. Device capability profile.
$device = null;
DeviceInfo::get(static function (DeviceInfo $info) use (&$device): void { $device = $info; });
Runtime::dispatchModuleResult(
    TestDiagnostics::$typedCall['requestId'],
    ModuleResultStatus::Success->value,
    Wire::map(['width' => 360.0, 'height' => 800.0, 'density' => 3.0, 'appearance' => 1, 'appState' => 1, 'memoryClassMb' => 128, 'lowRamDevice' => true, 'powerSaveMode' => true]),
);
$assert(
    $device instanceof DeviceInfo && $device->memoryClassMb === 128 && $device->lowRamDevice && $device->powerSaveMode && $device->constrained(),
    'DeviceInfo must expose memory class, low-RAM and power-save state.',
);
$assert($device->appVersion === '' && $device->buildNumber === '', 'A runtime without the version keys reports an unknown version.');
$device = null;
DeviceInfo::get(static function (DeviceInfo $info) use (&$device): void { $device = $info; });
Runtime::dispatchModuleResult(
    TestDiagnostics::$typedCall['requestId'],
    ModuleResultStatus::Success->value,
    Wire::map(['width' => 360.0, 'height' => 800.0, 'density' => 3.0, 'appearance' => 1, 'appState' => 1, 'appVersion' => ' 1.0.121 ', 'buildNumber' => '121', 'locale' => 'pt-BR']),
);
$assert($device instanceof DeviceInfo && $device->appVersion === '1.0.121' && $device->buildNumber === '121' && $device->locale === 'pt-BR', 'DeviceInfo must expose the installed app version, build number and system locale.');
$assert((new DeviceStatusValue(1.0, false, NetworkType::Wifi, false, true))->powerSaveMode(), 'DeviceStatus must expose power-save mode.');

// 7. Image prefetch.
$prefetched = null;
Image::prefetch(
    ['https://cdn.example.test/a.jpg', 'https://cdn.example.test/a.jpg', 'https://cdn.example.test/b.jpg'],
    MediaPriority::Visible,
    ['Authorization' => 'Bearer x'],
    static function (ImagePrefetchResult $result) use (&$prefetched): void { $prefetched = $result; },
    ['https://cdn.example.test/b.jpg' => 'avatar:b'],
);
$prefetch = $lastCall();
$assert(
    $prefetch['module'] === 'image' && $prefetch['method'] === 'prefetch'
        && json_decode((string) $prefetch['values']['urls'], true) === ['https://cdn.example.test/a.jpg', 'https://cdn.example.test/b.jpg']
        && $prefetch['values']['priority'] === MediaPriority::Visible->value
        && $prefetch['values']['headers'] === 'Authorization:Bearer x'
        && json_decode((string) $prefetch['values']['cacheKeys'], true) === ['', 'avatar:b'],
    'Image::prefetch must deduplicate URLs and pass priority, headers and cache keys.',
);
Runtime::dispatchModuleResult($prefetch['requestId'], ModuleResultStatus::Success->value, Wire::map(['succeeded' => 1, 'failed' => 1, 'bytes' => 2048]));
$assert($prefetched instanceof ImagePrefetchResult && $prefetched->succeeded === 1 && !$prefetched->complete(), 'Image::prefetch must report a typed result.');
$assert($rejects(static fn () => Image::prefetch(['file:///etc/passwd'])) && $rejects(static fn () => Image::prefetch([])), 'Image::prefetch accepts only remote images.');

// 8. Permission kinds.
$assert(
    PermissionKind::BluetoothConnect->value === 7 && PermissionKind::FullScreenIntent->value === 8
        && PermissionKind::PhoneState->value === 9 && PermissionStatus::Unavailable->value === 5,
    'New permission kinds must extend the sequential enums.',
);
$phoneDecision = null;
Permissions::status(PermissionKind::PhoneState, static function (PermissionDecision $decision) use (&$phoneDecision): void { $phoneDecision = $decision; });
$assert($lastCall()['values'] == ['kind' => 9], 'Permission kinds must bridge their integer values.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['status' => 5, 'canAskAgain' => false]));
$assert($phoneDecision instanceof PermissionDecision && $phoneDecision->unavailable() && !$phoneDecision->granted(), 'Unavailable permissions must not count as granted.');

// 10. Accessibility announcements.
$announced = null;
Accessibility::announce('Mensagem enviada', static function (bool $delivered) use (&$announced): void { $announced = $delivered; });
$assert($lastCall()['module'] === 'accessibility' && $lastCall()['values'] == ['text' => 'Mensagem enviada'], 'Accessibility::announce must bridge the text.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['delivered' => true]));
$assert($announced === true && $rejects(static fn () => Accessibility::announce('  ')), 'Accessibility::announce must report delivery and reject empty text.');

// 10b. Named secure claims, app text scale, biometrics and secure storage.
TestDiagnostics::$moduleCall = null;
SecureScreen::claim('app-lock');
$assert($lastCall()['values'] == ['enabled' => true] && SecureScreen::claimed('app-lock'), 'Screen::claim must enable secure mode.');
SecureScreen::claim('two-factor');
SecureScreen::release('app-lock');
$assert(SecureScreen::isSecure(), 'Releasing one claim must keep another owner secure.');
SecureScreen::release('two-factor');
$assert($lastCall()['values'] == ['enabled' => false] && !SecureScreen::isSecure(), 'Releasing the last claim must clear secure mode.');

$scale = null;
Accessibility::setTextScale(1.15, 1.6, static function (?\Pam\Native\System\TextScale $value) use (&$scale): void { $scale = $value; });
$assert($lastCall()['module'] === 'accessibility' && $lastCall()['method'] === 'setTextScale'
    && $lastCall()['values'] == ['multiplier' => 1.15, 'maxSystemScale' => 1.6], 'Accessibility::setTextScale must bridge multiplier and cap.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'multiplier' => 1.15, 'maxSystemScale' => 1.6, 'appliedMultiplier' => 1.0, 'appliedMaxSystemScale' => 0.0, 'systemScale' => 1.0, 'effectiveScale' => 1.0,
]));
$assert($scale instanceof \Pam\Native\System\TextScale && $scale->pendingRestart() && $scale->multiplier === 1.15, 'TextScale must expose the stored and applied values.');
$assert($rejects(static fn () => Accessibility::setTextScale(4.0)) && $rejects(static fn () => Accessibility::setTextScale(1.0, 0.5)), 'Text scale ranges must be validated.');

$secret = 'unset';
\Pam\Native\Storage\SecureStorage::get('app-lock:user', static function (?string $value) use (&$secret): void { $secret = $value; });
$assert($lastCall()['module'] === 'secure-storage' && $lastCall()['values'] == ['key' => 'app-lock:user'], 'SecureStorage::get must bridge the key.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['found' => false, 'value' => '']));
$assert($secret === null && $rejects(static fn () => \Pam\Native\Storage\SecureStorage::set('', 'x')), 'SecureStorage must answer null for missing keys and reject empty keys.');

// Request headers are a JSON object even when no optional headers are supplied.
// Android consumes this field with JSONObject; [] fails before any HTTP request.
foreach (['get', 'post', 'put', 'patch', 'delete', 'upload'] as $httpHelper) {
    $callback = static function (HttpResponse $_): void {};
    if ($httpHelper === 'upload') {
        Http::upload('https://api.example.test/object', 'audio/note.wav', $callback);
    } else {
        Http::{$httpHelper}('https://api.example.test/object', $callback);
    }
    $call = $lastCall();
    $assert($call['values']['headers'] === '{}', "Http::{$httpHelper} must encode empty request headers as a JSON object.");
    Runtime::dispatchModuleResult($call['requestId'], ModuleResultStatus::Success->value,
        Wire::map(['statusCode' => 204, 'body' => '']));
}

// HTTP response headers.
$headerResponse = null;
Http::get('https://api.example.test/clock', static function (HttpResponse $value) use (&$headerResponse): void { $headerResponse = $value; });
Runtime::dispatchModuleResult(TestDiagnostics::$moduleCall['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'statusCode' => 200, 'body' => '', 'headers' => '{"date":"Mon, 05 Oct 2026 12:00:00 GMT","x-request-id":"abc"}',
]));
$assert(
    $headerResponse instanceof HttpResponse && $headerResponse->header('X-Request-Id') === 'abc'
        && $headerResponse->date()?->format('U') === (string) gmmktime(12, 0, 0, 10, 5, 2026),
    'HttpResponse must expose case-insensitive headers and the server Date.',
);
$assert((new HttpResponse(200, ''))->date() === null, 'Responses without a Date header must return null.');
$assert((new HttpResponse(200, '', '', ['date' => 'not a date']))->date() === null, 'Malformed Date headers must return null.');

// Cancellable timers.
$ticks = 0;
$interval = \Pam\Native\System\Timers::every(1_000, static function () use (&$ticks): void { $ticks++; });
$firstTick = $lastCall();
$assert($firstTick['module'] === 'timers' && $firstTick['values'] == ['milliseconds' => 1_000, 'timer' => $interval->id], 'Repeating timers must arm a cancellable native timer.');
Runtime::dispatchModuleResult($firstTick['requestId'], ModuleResultStatus::Success->value, '');
$secondTick = $lastCall();
$assert($ticks === 1 && $secondTick['requestId'] !== $firstTick['requestId'] && $interval->active(), 'Repeating timers must re-arm after each tick.');
$interval->cancel();
$assert($lastCall()['method'] === 'cancel' && !$interval->active(), 'Cancelling a timer must notify the native host.');
Runtime::dispatchModuleResult($secondTick['requestId'], ModuleResultStatus::Success->value, '');
$assert($ticks === 1, 'Cancelled timers must never fire again.');
$fired = false;
$timeout = \Pam\Native\System\Timers::timeout(10, static function () use (&$fired): void { $fired = true; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, '');
$assert($fired && !$timeout->active(), 'One-shot timers must fire once and finish.');

// App lifecycle subscriptions.
$states = [];
$stateSubscription = \Pam\Native\App::onStateChange(static function (\Pam\Native\AppState $state) use (&$states): void { $states[] = $state; });
Runtime::dispatchEvent(0, \Pam\Native\EventKind::AppState->value, (string) \Pam\Native\AppState::Background->value);
Runtime::dispatchEvent(0, \Pam\Native\EventKind::AppState->value, (string) \Pam\Native\AppState::Active->value);
\Pam\Native\App::offStateChange($stateSubscription);
Runtime::dispatchEvent(0, \Pam\Native\EventKind::AppState->value, (string) \Pam\Native\AppState::Inactive->value);
$assert(
    $states === [\Pam\Native\AppState::Background, \Pam\Native\AppState::Active] && \Pam\Native\App::state() === \Pam\Native\AppState::Inactive,
    'App::onStateChange must deliver foreground/background transitions until unsubscribed.',
);

// ScrollView end reached.
$scroll = \Pam\Native\UI\ScrollView::make(Text::make('Feed'))->onEndReached(static fn () => null, 0.25)->toElement();
$assert(
    isset($scroll->events()[\Pam\Native\EventKind::EndReached->value])
        && $scroll->properties()[\Pam\Native\PropKey::EndReachedThreshold->value] === 0.25,
    'ScrollView must support on:endReached with a threshold.',
);

// Share/alert failures, permission rationale and failure callbacks.
$lastTyped = static function (): array {
    $call = TestDiagnostics::$typedCall ?? throw new RuntimeException('No typed native call was recorded.');

    return $call + ['values' => $call['payload'] === '' ? [] : Wire::decodeMap($call['payload'])];
};
$textOpened = false;
$textFailure = null;
Share::text('Oi', 'Enviar', static function () use (&$textOpened): void { $textOpened = true; }, static function (string $error) use (&$textFailure): void { $textFailure = $error; });
$assert($lastTyped()['operation'] === \Pam\Native\NativeOperation::Share->value && $lastTyped()['values'] == ['text' => 'Oi', 'title' => 'Enviar'], 'Share::text must keep its payload.');
Runtime::dispatchModuleResult($lastTyped()['requestId'], ModuleResultStatus::Failure->value, 'no presenter');
$assert(!$textOpened && $textFailure === 'no presenter', 'Share::text failures must reach $failure, not $opened.');
Share::text('Oi', opened: static function () use (&$textOpened): void { $textOpened = true; });
Runtime::dispatchModuleResult($lastTyped()['requestId'], ModuleResultStatus::Success->value, '');
$assert($textOpened, 'Share::text must report the presented sheet.');

$pressed = null;
\Pam\Native\System\Alert::choose('Apagar?', 'Sem volta', [
    \Pam\Native\System\AlertButton::cancel('Cancelar'),
    \Pam\Native\System\AlertButton::destructive('Apagar'),
], static function (int $index) use (&$pressed): void { $pressed = $index; });
$alertCall = $lastTyped();
$assert(
    $alertCall['operation'] === \Pam\Native\NativeOperation::Alert->value
        && $alertCall['values']['buttonCount'] === 2
        && $alertCall['values']['button0Label'] === 'Cancelar' && $alertCall['values']['button0Style'] === 2
        && $alertCall['values']['button1Label'] === 'Apagar' && $alertCall['values']['button1Style'] === 3,
    'Alert::choose must bridge labelled, styled buttons on the alert operation.',
);
Runtime::dispatchModuleResult($alertCall['requestId'], ModuleResultStatus::Success->value, Wire::map(['index' => 1]));
$assert($pressed === 1, 'Alert::choose must report the pressed index.');
\Pam\Native\System\Alert::choose('t', 'm', [\Pam\Native\System\AlertButton::make('Ok')], static function (int $index) use (&$pressed): void { $pressed = $index; });
Runtime::dispatchModuleResult($lastTyped()['requestId'], ModuleResultStatus::Success->value, '');
$assert($pressed === \Pam\Native\System\Alert::DISMISSED, 'A host without choice support must answer DISMISSED.');
$assert(
    $rejects(static fn () => \Pam\Native\System\Alert::choose('t', 'm', [], static fn (int $_) => null))
        && $rejects(static fn () => \Pam\Native\System\Alert::choose('t', 'm', array_fill(0, 4, \Pam\Native\System\AlertButton::make('a')), static fn (int $_) => null))
        && $rejects(static fn () => \Pam\Native\System\Alert::choose('t', 'm', [\Pam\Native\System\AlertButton::cancel('a'), \Pam\Native\System\AlertButton::cancel('b')], static fn (int $_) => null))
        && $rejects(static fn () => \Pam\Native\System\AlertButton::make(' ')),
    'Alert::choose must validate its buttons.',
);
$assert(\Pam\Native\System\AlertButtonStyle::Default->value === 1 && \Pam\Native\System\AlertButtonStyle::Destructive->value === 3, 'AlertButtonStyle must be sequential from 1.');

$showRationale = null;
Permissions::shouldShowRationale(PermissionKind::Camera, static function (bool $show) use (&$showRationale): void { $showRationale = $show; });
$assert($lastCall()['method'] === 'shouldShowRationale' && $lastCall()['values'] == ['kind' => 1], 'shouldShowRationale must bridge the kind.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['show' => true]));
$assert($showRationale === true, 'shouldShowRationale must report the platform flag.');

$rationale = new \Pam\Native\PermissionRationale('Câmera', 'Para fotos', 'Continuar', 'Agora não');
$decision = null;
$onDecision = static function (PermissionDecision $value) use (&$decision): void { $decision = $value; };
// No rationale needed: requests directly.
Permissions::requestKind(PermissionKind::Camera, $onDecision, rationale: $rationale);
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['show' => false]));
$assert($lastCall()['method'] === 'request', 'Without a platform rationale requestKind must request directly.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['status' => 1, 'canAskAgain' => false]));
$assert($decision instanceof PermissionDecision && $decision->granted(), 'The direct request must answer its decision.');
// Rationale shown and confirmed: requests.
Permissions::requestKind(PermissionKind::Camera, $onDecision, rationale: $rationale);
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['show' => true]));
$rationaleAlert = $lastTyped();
$assert(
    $rationaleAlert['operation'] === \Pam\Native\NativeOperation::Alert->value && $rationaleAlert['values']['title'] === 'Câmera'
        && $rationaleAlert['values']['button0Label'] === 'Agora não' && $rationaleAlert['values']['button1Label'] === 'Continuar',
    'The rationale must be explained in a native alert first.',
);
TestDiagnostics::$moduleCall = null;
Runtime::dispatchModuleResult($rationaleAlert['requestId'], ModuleResultStatus::Success->value, Wire::map(['index' => 1]));
$assert($lastCall()['method'] === 'request', 'A confirmed rationale must request the permission.');
// Rationale cancelled: answers the current status without requesting.
Permissions::requestKind(PermissionKind::Camera, $onDecision, rationale: $rationale);
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['show' => true]));
Runtime::dispatchModuleResult($lastTyped()['requestId'], ModuleResultStatus::Success->value, Wire::map(['index' => 0]));
$assert($lastCall()['method'] === 'status', 'A cancelled rationale must only read the current status.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['status' => 2, 'canAskAgain' => true]));
$assert($decision->status === PermissionStatus::Denied, 'A cancelled rationale must answer the denied status.');

$permissionFailure = null;
Permissions::status(PermissionKind::Camera, $onDecision, static function (string $error) use (&$permissionFailure): void { $permissionFailure = $error; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Failure->value, 'status failed');
Permissions::requestKind(PermissionKind::Camera, $onDecision, static function (string $error) use (&$permissionFailure): void { $permissionFailure .= '|'.$error; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Failure->value, 'request failed');
$assert($permissionFailure === 'status failed|request failed', 'Permission failures must reach $failure without throwing.');

$cacheFailure = '';
\Pam\Native\System\Caches::usage(static fn ($_) => null, static function (string $error) use (&$cacheFailure): void { $cacheFailure .= $error; });
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Failure->value, 'usage failed');
\Pam\Native\System\Caches::clear(static fn ($_) => null, failure: static function (string $error) use (&$cacheFailure): void { $cacheFailure .= '|'.$error; });
$assert($lastCall()['values'] == ['preserveOffline' => true], 'Caches::clear must keep preserving offline media by default.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Failure->value, 'clear failed');
$assert($cacheFailure === 'usage failed|clear failed', 'Cache failures must reach $failure without throwing.');
