<?php

declare(strict_types=1);

use Pam\Native\Bridge\Attributes\NativeMethod;
use Pam\Native\Bridge\Attributes\NativeModule;
use Pam\Native\Bridge\Attributes\NativePermission;
use Pam\Native\Bridge\ContractCompiler;
use Pam\Native\Bridge\NativeCallKind;
use Pam\Native\Diagnostics\ReplayTimeline;
use Pam\Native\HotReload\StateSnapshot;
use Pam\Native\HotReload\HotReloadCoordinator;
use Pam\Native\InternalHttp\LocalTransport;
use Pam\Native\InternalHttp\Request;
use Pam\Native\InternalHttp\Response;
use Pam\Native\InternalHttp\Router;
use Pam\Native\LocalFirst\ConflictPolicy;
use Pam\Native\LocalFirst\EncryptedJournal;
use Pam\Native\LocalFirst\LocalRecord;
use Pam\Native\LocalFirst\LocalStore;
use Pam\Native\Plugin\PluginRegistry;
use Pam\Native\Plugin\RegistryEntry;
use Pam\Native\Plugin\TrustTier;
use Pam\Native\Scheduling\AsyncStream;
use Pam\Native\Scheduling\BackpressureException;
use Pam\Native\Scheduling\DeadlineExceededException;
use Pam\Native\Scheduling\TaskGroup;

#[NativeModule(id: 1, name: 'Camera')]
interface SingularityCameraContract
{
    #[NativeMethod(id: 1)]
    #[NativePermission('camera.capture')]
    public function capture(string $quality, bool $flash): array;

    #[NativeMethod(id: 2, kind: NativeCallKind::Stream, timeoutMs: 60_000)]
    public function frames(int $maximumFps): array;
}

$contracts = ContractCompiler::compile([SingularityCameraContract::class], 'Pam.Showcase');
$assert(
    $contracts->manifest['schema'] === 2
        && $contracts->manifest['modules'][0]['methods'][0]['permissions'] === ['camera.capture']
        && str_contains($contracts->kotlin, 'object CameraContract')
        && str_contains($contracts->swift, 'enum CameraContract')
        && strlen($contracts->fingerprint) === 64,
    'Singularity contracts must generate deterministic typed PHP, Kotlin and Swift bindings.',
);

$group = new TaskGroup(deadlineMs: 1_000);
$group->async(static fn (): int => 20);
$group->async(static fn (): int => 22);
$assert($group->awaitAll() === [20, 22], 'Structured task groups must await ordered child results.');

$late = new TaskGroup(deadlineMs: 1);
$late->async(static function (): void { usleep(2_000); });
$deadlineExceeded = false;
try {
    $late->awaitAll();
} catch (DeadlineExceededException) {
    $deadlineExceeded = true;
}
$assert($deadlineExceeded, 'Structured task groups must enforce one deadline across all children.');

$stream = new AsyncStream(2);
$stream->emit('first');
$stream->emit('second');
$backpressure = false;
try {
    $stream->emit('overflow');
} catch (BackpressureException) {
    $backpressure = true;
}
$assert($backpressure && $stream->next() === 'first', 'Async streams must apply bounded backpressure.');

$local = new LocalStore();
$local->put('todos', '1', ['title' => 'offline'], 100);
$local->merge([
    new LocalRecord('todos', '1', ['title' => 'server'], 2, 200),
], ConflictPolicy::LatestWriteWins);
$assert(
    $local->get('todos', '1')?->attributes['title'] === 'server' && count($local->drainOutbox()) === 1,
    'Local-first storage must support optimistic outbox writes and deterministic conflict resolution.',
);
$journal = new EncryptedJournal(str_repeat('k', 32));
$sealed = $journal->seal([['id' => 1, 'value' => 'secret']]);
$assert(
    !str_contains($sealed, 'secret') && $journal->open($sealed)[0]['value'] === 'secret',
    'Local-first journals must be authenticated and encrypted at rest.',
);

$router = (new Router())
    ->middleware(static function (Request $request, Closure $next): Response {
        $response = $next($request);
        return new Response($response->status, $response->body, $response->headers + ['x-pam-local' => '1']);
    })
    ->route('POST', '/process', static fn (Request $request): Response => Response::json([
        'length' => strlen($request->body),
    ]));
$transport = new LocalTransport($router);
$response = $transport->send(new Request('POST', '/process', body: 'payload'));
$assert(
    $response->status === 200 && $response->headers['x-pam-local'] === '1' && !$transport->opensNetworkSocket(),
    'Internal HTTP must run middleware and handlers without opening a TCP socket.',
);

$snapshot = StateSnapshot::capture(['route' => '/checkout', 'form' => ['email' => 'a@b.test']]);
$assert(
    $snapshot->restore()['route'] === '/checkout' && strlen($snapshot->fingerprint) === 64,
    'Hot reload must preserve bounded, fingerprinted application state.',
);
$reloadState = ['route' => '/home'];
$reload = new HotReloadCoordinator();
$reload->register(
    'navigation',
    static fn (): array => $reloadState,
    static function (array $state) use (&$reloadState): void { $reloadState = $state; },
);
$reload->checkpoint([['kind' => 1, 'route' => '/checkout']]);
$reloadState = ['route' => '/empty'];
$assert(
    $reload->restore() && $reloadState['route'] === '/home',
    'Hot reload coordinator must restore registered state across a runtime restart.',
);
$reload->clear();

$timeline = new ReplayTimeline();
$timeline->record(1, ['action' => 'tap']);
$timeline->record(2, ['route' => '/checkout']);
$replayed = [];
$timeline->replay(static function (int $kind, array $payload) use (&$replayed): void {
    $replayed[] = [$kind, $payload];
});
$assert(
    count($replayed) === 2 && strlen($timeline->fingerprint()) === 64,
    'DevTools replay must preserve deterministic event order and identity.',
);

$registry = new PluginRegistry();
$registry->register(new RegistryEntry(
    'pushinbr/pam-native-camera',
    '1.0.0',
    TrustTier::Official,
    ['camera.capture'],
    str_repeat('a', 64),
    100,
));
$assert(
    $registry->authorize(
        'pushinbr/pam-native-camera',
        ['camera.capture'],
        minimumTrust: TrustTier::Verified,
    )->trust === TrustTier::Official,
    'Plugin registry policy must enforce immutable identity, capabilities and quality score.',
);
