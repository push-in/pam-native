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
        && $share['values']['mimeType'] === '' && $share['values']['title'] === 'Enviar',
    'Share::files must bridge private paths to the native share sheet.',
);
Runtime::dispatchModuleResult($share['requestId'], ModuleResultStatus::Success->value, '');
$assert($shared, 'Share::files must confirm the opened share sheet.');
$assert(
    $rejects(static fn () => Share::files([]))
        && $rejects(static fn () => Share::files(['/etc/passwd']))
        && $rejects(static fn () => Share::files(['a.txt'], 'not a mime')),
    'Share::files must validate paths and MIME types.',
);

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

// 4. Media library saving.
$savedAsset = null;
MediaLibrary::save('exports/photo.jpg', album: 'Zé Chat', callback: static function (MediaAsset $asset) use (&$savedAsset): void { $savedAsset = $asset; });
$save = $lastCall();
$assert(
    $save['module'] === 'media-library' && $save['method'] === 'save'
        && $save['values'] == ['path' => 'exports/photo.jpg', 'album' => 'Zé Chat', 'mimeType' => '', 'name' => ''],
    'MediaLibrary::save must bridge the private path and album.',
);
Runtime::dispatchModuleResult($save['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'item' => json_encode(['id' => '7', 'uri' => 'content://media/external/images/media/7', 'name' => 'photo.jpg', 'mimeType' => 'image/jpeg', 'size' => 10, 'albumTitle' => 'Zé Chat']),
]));
$assert($savedAsset instanceof MediaAsset && $savedAsset->id === '7' && $savedAsset->albumTitle === 'Zé Chat', 'MediaLibrary::save must return the typed saved asset.');
$assert(
    $rejects(static fn () => MediaLibrary::save('a.jpg', album: '../x'))
        && $rejects(static fn () => MediaLibrary::save('a.pdf', mimeType: 'application/pdf')),
    'MediaLibrary::save must reject unsafe albums and non-media MIME types.',
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

// 9. Conversation notifications, actions and declarative push rendering.
Notifications::conversation('chat:42')
    ->title('Viagem')
    ->group()
    ->self(Person::make('Você'))
    ->message(Person::make('Ana', 'https://cdn.example.test/ana.jpg', 'user-1'), 'Partiu?', 1_700_000_000_000, 'm1')
    ->ownMessage('Bora', 1_700_000_001_000)
    ->reply('Responder', ActionEndpoint::post('https://api.example.test/chats/{chat_id}/messages')->bearerFromStorage('auth.token')->json(['body' => '{reply}']))
    ->markRead('Marcar como lida')
    ->deepLink('zechat://chat/42')
    ->data(['chat_id' => '42'])
    ->importance(NotificationImportance::Urgent)
    ->show();
$conversation = $lastCall();
$spec = json_decode((string) $conversation['values']['spec'], true);
$assert(
    $conversation['method'] === 'showConversation'
        && $spec['key'] === 'chat:42' && $spec['group'] === true && $spec['self']['name'] === 'Você'
        && $spec['messages'][0] === ['id' => 'm1', 'text' => 'Partiu?', 'timestamp' => 1_700_000_000_000, 'sender' => ['name' => 'Ana', 'avatar' => 'https://cdn.example.test/ana.jpg', 'key' => 'user-1']]
        && !isset($spec['messages'][1]['sender'])
        && $spec['replyLabel'] === 'Responder' && $spec['markReadLabel'] === 'Marcar como lida'
        && $spec['replyEndpoint']['headers']['Authorization'] === 'Bearer {storage:auth.token}'
        && $spec['replyEndpoint']['body'] === ['body' => '{reply}']
        && $spec['markReadEndpoint'] === null
        && $spec['data'] === '{"chat_id":"42"}' && $spec['importance'] === 4,
    'Conversation notifications must serialize MessagingStyle messages, actions and endpoints.',
);
Notifications::cancelGroup('chat:42');
$assert($lastCall()['method'] === 'cancelConversation' && $lastCall()['values'] == ['key' => 'chat:42'], 'cancelGroup must cancel the conversation natively.');
$assert(
    $rejects(static fn () => Notifications::conversation('bad key'))
        && $rejects(static fn () => Notifications::conversation('chat:1')->show())
        && $rejects(static fn () => ActionEndpoint::post('ftp://x'))
        && $rejects(static fn () => Person::make('')),
    'Conversation notifications must validate keys, messages and endpoints.',
);

$actions = [];
Notifications::onAction(static function (NotificationAction $action) use (&$actions): void { $actions[] = $action; });
$actionCall = $lastCall();
$assert($actionCall['method'] === 'nextAction', 'Notification actions must long-poll the native queue.');
Runtime::dispatchModuleResult($actionCall['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'type' => 1, 'conversation' => 'chat:42', 'text' => 'Já vou', 'data' => '{"chat_id":"42"}', 'deepLink' => '',
    'handledNatively' => true, 'statusCode' => 201, 'timestamp' => 5,
]));
$assert(
    count($actions) === 1 && $actions[0]->type === NotificationActionType::Reply && $actions[0]->isReply()
        && $actions[0]->text === 'Já vou' && $actions[0]->data === ['chat_id' => '42'] && $actions[0]->delivered()
        && $lastCall()['method'] === 'nextAction',
    'Inline replies must reach PHP typed and re-arm the listener.',
);

Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map([
    'type' => 1, 'conversation' => 'chat:43', 'text' => 'Oi', 'data' => '{"chat_id":"43","user_id":"u-b"}', 'deepLink' => '',
    'handledNatively' => false, 'statusCode' => 0, 'timestamp' => 6, 'credentialMissing' => true,
]));
$assert(
    count($actions) === 2 && $actions[1]->credentialMissing && !$actions[1]->delivered() && !$actions[0]->credentialMissing,
    'Actions must report when the push account had no native credential.',
);

// 9b. Per-account notification credentials.
$credentialEndpoint = ActionEndpoint::post('https://api.example.test/chats/{chat_id}/messages')
    ->bearerFromCredential('user_id', 'recipient_user_id')
    ->json(['body' => '{reply}'])
    ->toArray();
$assert(
    $credentialEndpoint['headers']->Authorization === 'Bearer {credential:user_id|recipient_user_id}',
    'bearerFromCredential must resolve the token of the push account natively.',
);
$assert(
    $rejects(static fn () => ActionEndpoint::post('https://x.test/{credential:user_id}'))
        && $rejects(static fn () => ActionEndpoint::post('https://x.test')->json(['token' => '{credential:user_id}']))
        && $rejects(static fn () => ActionEndpoint::post('https://x.test')->bearerFromCredential('bad field'))
        && $rejects(static fn () => ActionEndpoint::post('https://x.test')->bearerFromCredential('a|b')),
    'Credentials must stay in headers and name plain push data fields.',
);
NotificationCredentials::set(' User-A ', 'token-a');
$assert($lastCall()['method'] === 'setCredential' && $lastCall()['values'] == ['account' => 'user-a', 'token' => 'token-a'], 'set must normalize the account id.');
NotificationCredentials::remove('USER-A');
$assert($lastCall()['method'] === 'removeCredential' && $lastCall()['values'] == ['account' => 'user-a'], 'remove must normalize the account id.');
NotificationCredentials::sync(['User-B' => 'token-b']);
$assert(
    $lastCall()['method'] === 'replaceCredentials' && $lastCall()['values'] == ['tokens' => '{"user-b":"token-b"}'],
    'sync must replace the native credential map.',
);
NotificationCredentials::clear();
$assert($lastCall()['method'] === 'replaceCredentials' && $lastCall()['values'] == ['tokens' => '{}'], 'clear must empty the native credential map.');
$assert(
    $rejects(static fn () => NotificationCredentials::set('', 't'))
        && $rejects(static fn () => NotificationCredentials::set('a', "t\nx"))
        && $rejects(static fn () => NotificationCredentials::set('a', '')),
    'Credentials must validate accounts and tokens.',
);

PushRendering::forType('chat.message')
    ->conversation('{chat_id}', title: '{chat_title}', group: '{is_group}')
    ->message(sender: '{sender_name}', text: '{body}', avatar: '{sender_avatar}', timestamp: '{sent_at}', id: '{message_id}')
    ->self('Você')
    ->reply('Responder')
    ->markRead('Marcar como lida')
    ->deepLink('zechat://chat/{chat_id}')
    ->channel('messages', 'Mensagens')
    ->suppressWhenRoute('chat/{chat_id}')
    ->suppressWhenRoute('chat', ['chatId' => '{chat_id}'])
    ->register();
$rule = json_decode((string) $lastCall()['values']['rule'], true);
$assert(
    $lastCall()['method'] === 'registerPushRendering'
        && $rule['type'] === 'chat.message' && $rule['field'] === 'type' && $rule['kind'] === 1
        && $rule['conversation'] === ['key' => '{chat_id}', 'title' => '{chat_title}', 'group' => '{is_group}']
        && $rule['message']['sender'] === '{sender_name}' && $rule['self'] === 'Você'
        && $rule['reply'] === ['label' => 'Responder', 'endpoint' => null]
        && $rule['suppress'] === [['path' => 'chat/{chat_id}'], ['route' => 'chat', 'params' => ['chatId' => '{chat_id}']]]
        && $rule['channelId'] === 'messages',
    'PushRendering must persist a declarative conversation rule.',
);
PushRendering::forType('chat.read')->dismissConversation('{chat_id}')->register();
$assert(json_decode((string) $lastCall()['values']['rule'], true)['kind'] === 3, 'PushRendering must support dismiss rules.');
$assert(
    $rejects(static fn () => PushRendering::forType('x')->register())
        && $rejects(static fn () => PushRendering::forType('x')->conversation('{a}')->notification()),
    'PushRendering rules need exactly one presentation.',
);

$chatNavigator = new Navigator(
    initialRoute: 'inbox',
    routes: [
        'inbox' => static fn () => Screen::make(Text::make('Inbox')),
        'chat' => static fn () => Screen::make(Text::make('Chat')),
    ],
    persistenceKey: 'push-route-test',
    deepLinks: [new DeepLink('/chat/{chatId}', 'chat')],
);
$chatNavigator->navigate('chat', ['chatId' => '42']);
$chatNavigator->render();
$route = $lastCall();
$assert(
    $route['method'] === 'setActiveRoute' && $route['values']['name'] === 'chat'
        && json_decode((string) $route['values']['params'], true) === ['chatId' => '42']
        && $route['values']['path'] === '/chat/42',
    'Focused routes must be reported for push suppression once rules need them.',
);
PushRendering::forget('chat.read');
$assert($lastCall()['method'] === 'forgetPushRendering' && $lastCall()['values'] == ['type' => 'chat.read', 'field' => 'type'], 'PushRendering::forget must remove one rule.');

// 10. Accessibility announcements.
$announced = null;
Accessibility::announce('Mensagem enviada', static function (bool $delivered) use (&$announced): void { $announced = $delivered; });
$assert($lastCall()['module'] === 'accessibility' && $lastCall()['values'] == ['text' => 'Mensagem enviada'], 'Accessibility::announce must bridge the text.');
Runtime::dispatchModuleResult($lastCall()['requestId'], ModuleResultStatus::Success->value, Wire::map(['delivered' => true]));
$assert($announced === true && $rejects(static fn () => Accessibility::announce('  ')), 'Accessibility::announce must report delivery and reject empty text.');

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
