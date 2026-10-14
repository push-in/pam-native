<?php

declare(strict_types=1);

use Pam\Native\AppState;
use Pam\Native\Cache\BoundedStore;
use Pam\Native\Cache\CallbackPersistence;
use Pam\Native\Cache\IdLedger;
use Pam\Native\Cache\MemoryPersistence;
use Pam\Native\Component;
use Pam\Native\Events\EventBus;
use Pam\Native\Events\Observable;
use Pam\Native\Events\Subscription;
use Pam\Native\Internal\ComponentLifecycle;
use Pam\Native\Internal\Runtime;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Renderable;
use Pam\Native\Scheduling\Debouncer;
use Pam\Native\Scheduling\SingleFlight;
use Pam\Native\Scheduling\Throttler;
use Pam\Native\System\ComponentTimer;
use Pam\Native\System\TimerBackgroundPolicy;
use Pam\Native\UI\Text as TextView;
use Pam\Native\Store\OptimisticOverlay;
use Pam\Native\Support\DateStyle;
use Pam\Native\Support\Format;
use Pam\Native\Support\RelativeTimeStyle;
use Pam\Native\Support\Text;
use Pam\Native\Sync\BackoffPolicy;
use Pam\Native\Sync\DrainTrigger;
use Pam\Native\Sync\MutationStatus;
use Pam\Native\Sync\OfflineMutationQueue;

// 1.40.0 framework utilities: events, component timers, rate limiters, bounded caches.

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) {
        throw new RuntimeException($message);
    }
};
$throws = static function (Closure $operation): bool {
    try {
        $operation();
    } catch (Throwable) {
        return true;
    }

    return false;
};
/** Request id of the last native timer armed. */
$armed = static function (): int {
    $call = TestDiagnostics::$moduleCall;
    if ($call === null || $call['module'] !== 'timers') {
        throw new RuntimeException('No native timer was armed.');
    }

    return $call['requestId'];
};
$fire = static function (int $requestId): void {
    Runtime::dispatchModuleResult($requestId, ModuleResultStatus::Success->value, '');
};

// EventBus: named and typed events, once, cancel, error isolation, bounds.
final class UtilitiesMessageRead
{
    public function __construct(public readonly int $id)
    {
    }
}
$bus = new EventBus(maxListeners: 4);
$received = [];
$named = $bus->on('feed.refresh', static function (string $reason) use (&$received): void { $received[] = "named:{$reason}"; });
$typed = $bus->on(UtilitiesMessageRead::class, static function (UtilitiesMessageRead $event) use (&$received): void { $received[] = "typed:{$event->id}"; });
$bus->once('feed.refresh', static function () use (&$received): void { $received[] = 'once'; });
$assert(
    $bus->emit('feed.refresh', 'pull') === 2 && $bus->emit(new UtilitiesMessageRead(7)) === 1
        && $bus->emit('feed.refresh', 'tab') === 1 && $bus->emit('missing') === 0
        && $received === ['named:pull', 'once', 'typed:7', 'named:tab'],
    'EventBus must deliver named and typed events in order and drop once listeners after delivery.',
);
$named->cancel();
$assert(!$named->active() && $bus->listeners('feed.refresh') === 0 && $bus->listeners() === 1, 'Cancelling a subscription must remove its listener.');
$named->cancel();
$skipped = [];
$second = null;
$bus->on('race', static function () use (&$second, &$skipped): void { $skipped[] = 'first'; $second?->cancel(); });
$second = $bus->on('race', static function () use (&$skipped): void { $skipped[] = 'second'; });
$bus->emit('race');
$assert($skipped === ['first'], 'A listener cancelled during an emit must not run.');
$bus->clear();
$after = [];
$bus->on('boom', static function (): void { throw new LogicException('first failed'); });
$bus->on('boom', static function () use (&$after): void { $after[] = 'ran'; });
$error = null;
try {
    $bus->emit('boom');
} catch (LogicException $caught) {
    $error = $caught->getMessage();
}
$assert($error === 'first failed' && $after === ['ran'], 'A failing listener must not stop the others and must rethrow afterwards.');
$bus->clear();
for ($i = 0; $i < 4; ++$i) {
    $bus->on("e{$i}", static fn () => null);
}
$assert($throws(static fn () => $bus->on('overflow', static fn () => null)) && $throws(static fn () => $bus->on('', static fn () => null)), 'EventBus must bound listeners and reject empty names.');
$assert(EventBus::shared('chat') === EventBus::shared('chat') && EventBus::shared('chat') !== EventBus::shared(), 'Shared buses must be singletons by name.');
EventBus::shared('chat')->on('x', static fn () => null);
EventBus::resetRuntime();
$assert(EventBus::shared('chat')->listeners() === 0, 'Runtime reset must empty shared buses.');
$adapted = 0;
$adapter = Subscription::from(static function () use (&$adapted): void { ++$adapted; });
$adapter->dispose();
$adapter->dispose();
$assert($adapted === 1 && $adapter->disposed(), 'Subscription::from must cancel the wrapped API exactly once.');

// Observable.
$unread = new Observable(0);
$changes = [];
$watch = $unread->on(static function (int $now, int $before) use (&$changes): void { $changes[] = "{$before}>{$now}"; }, immediate: true);
$unread->set(0);
$unread->update(static fn (int $n): int => $n + 2);
$watch->cancel();
$unread->set(5);
$assert($changes === ['0>0', '0>2'] && $unread->peek() === 5 && $unread->get() === 5 && $unread->listeners() === 0, 'Observable must notify only real changes until cancelled.');

// Component ownership: subscriptions and timers stop on unmount and follow the background policy.
final class UtilitiesOwnerFixture extends Component
{
    public int $events = 0;
    public int $ticks = 0;
    public int $timeouts = 0;
    public ?ComponentTimer $poll = null;
    public ?ComponentTimer $hide = null;
    public ?ComponentTimer $dropped = null;
    public ?Debouncer $search = null;

    public function __construct(private readonly EventBus $bus, private readonly Observable $value)
    {
    }

    public function mount(): void
    {
        $this->bus->on('ping', fn () => ++$this->events, owner: $this);
        $this->own($this->value->on(fn () => ++$this->events));
        $this->poll = $this->timers()->every(1_000, fn () => ++$this->ticks);
        $this->hide = $this->timers()->timeout(5_000, fn () => ++$this->timeouts);
        $this->dropped = $this->timers()->timeout(5_000, fn () => ++$this->timeouts, TimerBackgroundPolicy::Cancel);
        $this->search = $this->timers()->debounce(300, fn () => null);
    }

    public function render(): Renderable
    {
        return TextView::make('owner');
    }

    public function timerCount(): int
    {
        return $this->timers()->count();
    }
}
$ownerBus = new EventBus();
$ownerValue = new Observable(1);
$owner = new UtilitiesOwnerFixture($ownerBus, $ownerValue);
ComponentLifecycle::beginRender();
$owner->toElement();
ComponentLifecycle::finishRender();
ComponentLifecycle::commit();
$ownerBus->emit('ping');
$ownerValue->set(2);
$assert($owner->events === 2 && $owner->timerCount() === 3 && $owner->poll?->repeating() === true, 'Owned subscriptions and timers must be live while mounted.');
$dropped = $armed();
ComponentLifecycle::appState(AppState::Background);
$assert(
    $owner->poll->suspended() && $owner->hide?->suspended() === true && $owner->dropped?->active() === false
        && $owner->timerCount() === 2,
    'Backgrounding must suspend timers and cancel Cancel-policy timers.',
);
$fire($dropped);
$assert($owner->timeouts === 0, 'A timer cancelled in the background must never fire.');
ComponentLifecycle::appState(AppState::Active);
$assert(!$owner->poll->suspended() && $owner->hide->active(), 'Returning to the foreground must re-arm suspended timers.');
$hide = $armed();
$fire($hide);
$assert($owner->timeouts === 1 && !$owner->hide->active() && $owner->timerCount() === 1, 'A re-armed timeout must fire once and leave the component registry.');
$owner->search?->call();
$assert($owner->search->pending(), 'Owned debouncers must accept calls while mounted.');
ComponentLifecycle::beginRender();
ComponentLifecycle::finishRender();
ComponentLifecycle::commit();
$ownerBus->emit('ping');
$ownerValue->set(3);
$assert(
    $owner->events === 2 && $ownerBus->listeners() === 0 && $ownerValue->listeners() === 0
        && !$owner->poll->active() && $owner->timerCount() === 0 && $owner->search->disposed(),
    'Unmount must cancel owned subscriptions, timers and debouncers.',
);
ComponentLifecycle::shutdown();

// Debouncer.
$searches = [];
$debounce = new Debouncer(300, static function (string $query) use (&$searches): void { $searches[] = $query; });
$debounce->call('a');
$first = $armed();
$debounce->call('ab');
$latest = $armed();
$fire($first);
$assert($searches === [] && $debounce->pending(), 'A superseded debounce timer must not run the action.');
$fire($latest);
$assert($searches === ['ab'] && !$debounce->pending(), 'A debouncer must run once with the last arguments.');
$debounce->call('abc');
$assert($debounce->flush() && $searches === ['ab', 'abc'] && !$debounce->flush(), 'flush() must run a pending call immediately once.');
$debounce->call('x');
$debounce->cancel();
$fire($armed());
$assert($searches === ['ab', 'abc'], 'cancel() must drop a pending call.');
$bounded = new Debouncer(0, static function (string $query) use (&$searches): void { $searches[] = "max:{$query}"; }, maxWaitMs: 0);
$bounded->call('now');
$assert(end($searches) === 'max:now', 'maxWait must flush a continuous stream of calls.');
$assert($throws(static fn () => new Debouncer(100, static fn () => null, 50)), 'maxWait shorter than the delay must be rejected.');

// Throttler.
$sent = [];
$throttle = new Throttler(1_000, static function (int $value) use (&$sent): void { $sent[] = $value; });
$throttle->call(1);
$window = $armed();
$throttle->call(2);
$throttle->call(3);
$assert($sent === [1] && $throttle->throttling(), 'The leading call must run at once and later calls wait.');
$fire($window);
$assert($sent === [1, 3] && $throttle->throttling(), 'The trailing call must run with the last arguments and reopen the window.');
$fire($armed());
$assert(!$throttle->throttling(), 'An idle window must close.');
$trailingOnly = new Throttler(500, static function (int $value) use (&$sent): void { $sent[] = $value * 10; }, leading: false);
$trailingOnly->call(4);
$assert($sent === [1, 3], 'Trailing-only throttles must wait for the window.');
$fire($armed());
$assert($sent === [1, 3, 40], 'Trailing-only throttles must run when the window closes.');
$trailingOnly->dispose();
$trailingOnly->call(5);
$assert($sent === [1, 3, 40] && $throws(static fn () => new Throttler(0, static fn () => null)), 'Disposed throttlers ignore calls; invalid intervals are rejected.');

// SingleFlight.
$flights = new SingleFlight(maxKeys: 2);
$pendingDone = null;
$starts = 0;
$results = [];
$work = static function (Closure $done) use (&$pendingDone, &$starts): void { ++$starts; $pendingDone = $done; };
$assert($flights->run('feed', $work, static function (mixed $value) use (&$results): void { $results[] = "a:{$value}"; }), 'The first caller must start the flight.');
$assert(!$flights->run('feed', $work, static function (mixed $value) use (&$results): void { $results[] = "b:{$value}"; }) && $starts === 1, 'Concurrent callers must join the flight.');
$pendingDone('v1');
$pendingDone('ignored');
$assert($results === ['a:v1', 'b:v1'] && !$flights->inFlight('feed'), 'Every waiter must receive the single result once.');
$flights->run('feed', $work, static function (mixed $value) use (&$results): void { $results[] = "c:{$value}"; });
$flights->run('feed', $work, static function (mixed $value) use (&$results): void { $results[] = "d:{$value}"; }, again: true);
$pendingDone('v2');
$assert($results === ['a:v1', 'b:v1', 'c:v2'] && $starts === 3 && $flights->inFlight('feed'), 'again must schedule one fresh run for late callers.');
$pendingDone('v3');
$assert(end($results) === 'd:v3' && !$flights->inFlight('feed'), 'Late callers must receive the fresher result.');
$failure = null;
$flights->run('err', static function (): void { throw new RuntimeException('offline'); }, static function (mixed $value, ?Throwable $error) use (&$failure): void { $failure = $error?->getMessage(); });
$assert($failure === 'offline' && $flights->count() === 0, 'Exceptions thrown by work must reach the waiters.');
$flights->run('k1', $work);
$flights->run('k2', $work);
$assert($throws(static fn () => $flights->run('k3', $work)), 'SingleFlight must bound concurrent keys.');
$flights->forget('k1');
$assert(!$flights->inFlight('k1') && $flights->count() === 1, 'forget() must drop a flight.');

// BoundedStore: LRU, TTL, bytes, scopes and persistence.
$now = 1_000_000;
$clock = static function () use (&$now): int { return $now; };
$lru = new BoundedStore('test.lru', maxItems: 3, clock: $clock);
$lru->put('a', 1);
$lru->put('b', 2);
$lru->put('c', 3);
$lru->get('a');
$lru->put('d', 4);
$assert($lru->keys() === ['c', 'a', 'd'] && !$lru->has('b') && $lru->peek('c') === 3 && $lru->keys()[0] === 'c', 'BoundedStore must evict the least recently used entry.');
$assert($lru->remember('a', static fn () => 99) === 1 && $lru->remember('z', static fn () => 26) === 26 && $lru->count() === 3, 'remember() must reuse or compute values.');
$ttl = new BoundedStore('test.ttl', ttlMs: 1_000, clock: $clock);
$ttl->put('short', 's', 100);
$ttl->put('long', 'l');
$now += 500;
$assert($ttl->get('short') === null && $ttl->get('long') === 'l' && $ttl->get('missing', 'd') === 'd', 'TTL entries must expire.');
$now += 600;
$assert($ttl->prune() === 1 && $ttl->count() === 0, 'prune() must remove expired entries.');
$budget = new BoundedStore('test.bytes', maxItems: 100, maxBytes: 30, clock: $clock);
$budget->put('a', str_repeat('x', 10));
$budget->put('b', str_repeat('y', 10));
$budget->put('c', str_repeat('z', 10));
$assert($budget->bytes() <= 30 && !$budget->has('a') && $budget->has('c'), 'The byte budget must evict the oldest entries.');
$assert(!$budget->put('huge', str_repeat('h', 100)) && !$budget->has('huge'), 'A value larger than the budget must be rejected.');
$sized = new BoundedStore('test.sized', maxBytes: 10, sizeOf: static fn (mixed $value): int => 4);
$sized->put('1', 'a');
$sized->put('2', 'b');
$sized->put('3', 'c');
$assert($sized->count() === 2 && $sized->bytes() === 8, 'A custom sizeOf must drive the byte budget.');
$assert($sized->trim(0.5) === 1 && $sized->keys() === ['3'] && $sized->trim(0.0) === 1, 'trim() must keep the most recent fraction.');
$assert($throws(static fn () => new BoundedStore('bad name')) && $throws(static fn () => new BoundedStore('ok', maxItems: 0)), 'Invalid store names and bounds must be rejected.');

$disk = new MemoryPersistence();
$profiles = new BoundedStore('profiles', maxItems: 3, ttlMs: 10_000, scope: 'account:1', persistence: $disk, clock: $clock);
$profiles->put('u1', ['name' => 'Ana']);
$profiles->put('u2', ['name' => 'Bia']);
$assert($profiles->dirty() && $profiles->persist() && !$profiles->persist() && isset($disk->values['pam.cache.profiles.account:1']), 'persist() must write only when entries changed.');
$profiles->useScope('account:2');
$assert($profiles->count() === 0 && $profiles->get('u1') === null && $profiles->storageKey() === 'pam.cache.profiles.account:2', 'Switching scope must hide the previous account entries.');
$profiles->put('u9', ['name' => 'Other']);
$profiles->useScope('account:1');
$restoredCount = -1;
$profiles->put('u3', ['name' => 'Cris']);
$profiles->hydrate(static function (int $count) use (&$restoredCount): void { $restoredCount = $count; });
$assert(
    $restoredCount === 2 && $profiles->keys() === ['u1', 'u2', 'u3'] && $profiles->get('u1') === ['name' => 'Ana']
        && isset($disk->values['pam.cache.profiles.account:2']),
    'hydrate() must restore the scope snapshot behind newer memory entries; the old scope is persisted on switch.',
);
$now += 20_000;
$fresh = new BoundedStore('profiles', scope: 'account:1', persistence: $disk, clock: $clock);
$fresh->hydrate();
$assert($fresh->count() === 0, 'Expired persisted entries must not be restored.');
$assert($fresh->import('not json') === 0 && $fresh->import('{"v":2,"entries":[]}') === 0, 'Invalid snapshots must be ignored.');
$asyncLoads = [];
$async = new BoundedStore('async', persistence: new CallbackPersistence(
    static function (string $key, Closure $done) use (&$asyncLoads): void { $asyncLoads[] = $done; },
    static function (string $key, string $value, ?Closure $done): void { $done?->__invoke(); },
), scope: 'a');
$async->hydrate();
$async->useScope('b');
($asyncLoads[0])('{"v":1,"entries":[["k",1,null]]}');
$assert($async->count() === 0, 'A snapshot that arrives after a scope switch must be discarded.');
$unencodable = new BoundedStore('objects', persistence: $disk);
$unencodable->put('closure', static fn () => null);
$unencodable->put('ok', [1, 2]);
$assert($unencodable->export() === '{"v":1,"entries":[["ok",[1,2],null]]}', 'Values that cannot be encoded must stay in memory only.');
$numeric = new BoundedStore('numeric', maxItems: 2);
$numeric->put('10', 'a');
$numeric->put('20', 'b');
$numeric->put('30', 'c');
$assert($numeric->keys() === ['20', '30'], 'Numeric string keys must keep string identity.');

// replace(), snapshot limits and storage-safe keys.
$fifo = new BoundedStore('fifo', maxItems: 2);
$fifo->put('a', 1);
$fifo->put('b', 2);
$assert($fifo->replace('a', 10) && !$fifo->replace('missing', 1) && $fifo->keys() === ['a', 'b'] && $fifo->peek('a') === 10, 'replace() must update in place without refreshing recency.');
$fifo->put('c', 3);
$assert($fifo->keys() === ['b', 'c'], 'A replaced entry must keep its eviction position.');
$smallDisk = new MemoryPersistence(maxValueBytes: 80);
$trimmed = new BoundedStore('trimmed', persistence: $smallDisk);
foreach (range(1, 10) as $i) {
    $trimmed->put("k{$i}", str_repeat('x', 10));
}
$trimmed->persist();
$snapshot = $smallDisk->values['pam.cache.trimmed'];
$assert(strlen($snapshot) <= 80 && str_contains($snapshot, '"k10"') && !str_contains($snapshot, '"k1"'), 'Snapshots must keep the newest entries within the backend limit.');
$assert(
    \Pam\Native\Storage\Storage::safeKey('pam.cache.profiles.account:1') === 'pam.cache.profiles.account-1'
        && preg_match(\Pam\Native\Storage\Storage::KEY_PATTERN, \Pam\Native\Storage\Storage::safeKey(str_repeat('é', 100))) === 1
        && preg_match(\Pam\Native\Storage\Storage::KEY_PATTERN, \Pam\Native\Storage\Storage::safeKey('')) === 1,
    'Storage::safeKey must produce keys the native store accepts.',
);
$storageScoped = new BoundedStore('profiles', scope: 'account:9', persistence: new \Pam\Native\Cache\StoragePersistence());
$storageScoped->put('u', 1);
$storageScoped->persist();
$storageCall = TestDiagnostics::$typedCall;
$assert($storageCall !== null && str_contains($storageCall['payload'], 'pam.cache.profiles.account-9') && (new \Pam\Native\Cache\StoragePersistence())->maxValueBytes() === 240_000, 'StoragePersistence must write storage-safe keys within the native limit.');

// StorageBudget.
$budgetItems = array_map(static fn (int $i): array => ['id' => $i, 'body' => str_repeat('b', 50)], range(1, 100));
$trimmedList = \Pam\Native\Storage\StorageBudget::trimList($budgetItems, 60, 1_000);
$assert(count($trimmedList) < 60 && $trimmedList[0]['id'] === 1 && strlen((string) json_encode($trimmedList)) <= 1_000, 'trimList must keep the head within the byte budget.');
$assert(\Pam\Native\Storage\StorageBudget::encodeList([1, 2, 3], 2) === '[1,2]' && \Pam\Native\Storage\StorageBudget::encodeObject(['a' => str_repeat('x', 100)], 50) === '', 'encodeList/encodeObject must bound values.');
$assert(\Pam\Native\Storage\StorageBudget::encodeMap(['old' => 1, 'mid' => 2, 'new' => 3], 2) === '{"mid":2,"new":3}' && \Pam\Native\Storage\StorageBudget::encodeMap([]) === '{}', 'encodeMap must keep the most recent entries.');

// IdLedger.
$ledgerDisk = new MemoryPersistence();
$deleted = new IdLedger('feed.deleted', maxIds: 3, ttlMs: 1_000, persistence: $ledgerDisk, clock: $clock);
foreach ([1, 2, 3, 4] as $id) {
    $deleted->add($id);
}
$assert($deleted->ids() === ['2', '3', '4'] && !$deleted->has(1) && $deleted->has('4'), 'IdLedger must keep only the newest ids.');
$posts = [['id' => 1], ['id' => 2], ['id' => 5]];
$assert($deleted->filter($posts, static fn (array $post): int => $post['id']) === [['id' => 1], ['id' => 5]], 'IdLedger::filter must drop recorded ids.');
$deleted->persist();
$reloaded = new IdLedger('feed.deleted', maxIds: 3, persistence: $ledgerDisk, clock: $clock);
$reloaded->hydrate();
$assert($reloaded->count() === 3 && $reloaded->has(3), 'IdLedger must restore persisted ids.');
$now += 2_000;
$assert(!$reloaded->has(2) && $reloaded->count() === 0, 'Persisted ids must keep their TTL.');
$assert($deleted->remove(4) && !$deleted->has(4), 'remove() must forget an id.');


// OptimisticOverlay.
$overlay = new OptimisticOverlay(maxEntries: 2);
$notified = [];
$overlaySubscription = $overlay->on(static function (string $key, array $fields) use (&$notified): void { $notified[] = $key; });
$row = ['id' => 1, 'liked' => false, 'likes' => 10];
$overlay->begin('post:1', ['liked' => true, 'likes' => 11]);
$assert($overlay->apply('post:1', $row) === ['id' => 1, 'liked' => true, 'likes' => 11] && $overlay->pending('post:1'), 'begin() must overlay the optimistic fields.');
$overlay->begin('post:1', ['liked' => false, 'likes' => 10]);
$overlay->settle('post:1', ['likes' => 99]);
$assert($overlay->fields('post:1') === ['liked' => false, 'likes' => 10] && $overlay->pending('post:1'), 'Server fields must wait while a newer request is in flight.');
$overlay->rollback('post:1', ['liked' => true, 'likes' => 11], ['liked' => false, 'likes' => 10]);
$assert($overlay->fields('post:1') === ['liked' => false, 'likes' => 10] && !$overlay->pending('post:1'), 'rollback() must keep fields a later tap changed.');
$overlay->begin('post:2', ['saved' => true]);
$overlay->rollback('post:2', ['saved' => true], ['saved' => false]);
$assert($overlay->fields('post:2') === ['saved' => false], 'rollback() must restore fields the failed tap still owns.');
$overlay->begin('post:3', ['liked' => true]);
$overlay->begin('post:4', ['liked' => true]);
$assert($overlay->count() === 2 && $overlay->fields('post:1') === null && $overlay->pending('post:3') && $overlay->pending('post:4'), 'LRU eviction must drop settled entries and keep pending ones.');
$overlay->settle('post:3', ['likes' => 5]);
$beforeRefresh = $overlay->revision();
$overlay->settle('post:4');
$assert($overlay->dropSettled(['post:3', 'post:4'], $beforeRefresh) === 1 && $overlay->fields('post:4') !== null, 'dropSettled() must drop only entries written before the refresh revision.');
$rows = $overlay->applyAll([['id' => 4, 'liked' => false], ['id' => 9, 'liked' => false]], static fn (array $item): string => "post:{$item['id']}");
$assert($rows[0]['liked'] === true && $rows[1]['liked'] === false, 'applyAll() must resolve every row by key.');
$overlay->set('post:9', ['liked' => true]);
$assert(in_array('post:9', $notified, true) && !$overlay->pending('post:9'), 'set() must write confirmed fields and notify.');
$count = count($notified);
$overlaySubscription->cancel();
$overlay->set('post:9', ['liked' => false]);
$assert(count($notified) === $count, 'Cancelled overlay listeners must not be notified.');
$overlayNow = 10_000;
$quiet = new OptimisticOverlay(clock: static function () use (&$overlayNow): int { return $overlayNow; });
$quietEvents = 0;
$quiet->on(static function () use (&$quietEvents): void { ++$quietEvents; });
$quiet->set('a', ['liked' => true]);
$quiet->begin('a', ['liked' => true]);
$quiet->rollback('a', ['liked' => false], ['liked' => false]);
$assert($quietEvents === 1 && !$quiet->pending('a'), 'Listeners must be notified only when fields change.');
$overlayNow += 40_000;
$quiet->set('b', ['liked' => true]);
$assert($quiet->dropSettled(['a', 'b'], olderThanMs: 30_000) === 1 && $quiet->fields('b') !== null && $quiet->fields('a') === null, 'dropSettled(olderThanMs:) must drop only old settled entries.');
$resolved = new OptimisticOverlay(resolver: static fn (array $row, array $fields): array => [...$row, 'resolved' => true]);
$resolved->set('a', ['x' => 1]);
$assert($resolved->apply('a', ['id' => 'a']) === ['id' => 'a', 'resolved' => true] && $resolved->apply('b', ['id' => 'b']) === ['id' => 'b'], 'A custom resolver must merge overlay rows.');
$overlay->clear();
$assert($overlay->count() === 0, 'clear() must drop every overlay.');

// Format and Text.
$assert(
    Format::count(1234567) === '1.234.567' && Format::count(1234567, 'en') === '1,234,567'
        && Format::countLabel(1, 'item', 'itens') === '1 item' && Format::countLabel(1234, 'item', 'itens') === '1.234 itens',
    'Format::count must group thousands per locale.',
);
$assert(
    Format::compact(999) === '999' && Format::compact(1000) === '1 mil' && Format::compact(1234) === '1,2 mil'
        && Format::compact(12_345) === '12 mil' && Format::compact(1_250_000) === '1,3 mi' && Format::compact(2_000_000_000) === '2 bi'
        && Format::compact(-1500) === '-1,5 mil' && Format::compact(999_999) === '1 mi',
    'Format::compact must abbreviate pt-BR magnitudes and roll over units.',
);
$assert(
    Format::compact(1234, 'en') === '1.2K' && Format::compact(1_500_000, 'en') === '1.5M'
        && Format::compact(1990, truncate: true) === '1,9 mil' && Format::compact(12_345, truncate: true, decimalsBelow: 1_000) === '12,3 mil'
        && Format::compact(9_999, plainBelow: 10_000) === '9.999' && Format::compact(15_900, truncate: true) === '15 mil',
    'Format::compact options must cover truncation, plain ranges and decimals.',
);
$base = 1_760_000_000;
$assert(
    Format::relative($base, $base + 30) === 'agora' && Format::relative($base, $base + 300) === 'há 5 min'
        && Format::relative($base, $base + 7_200) === 'há 2 h' && Format::relative($base, $base + 3 * 86_400) === 'há 3 d'
        && Format::relative($base, $base + 14 * 86_400) === 'há 2 sem' && Format::relative($base, $base - 50) === 'agora'
        && Format::relative('', $base) === '' && Format::relative('not a date', $base) === '',
    'Format::relative short pt-BR must follow the feed wording.',
);
$assert(
    Format::relative($base, $base + 60, RelativeTimeStyle::Long) === 'há 1 minuto'
        && Format::relative($base, $base + 7_200, RelativeTimeStyle::Long) === 'há 2 horas'
        && Format::relative($base, $base + 300, RelativeTimeStyle::Narrow) === '5 min'
        && Format::relative($base, $base + 300, locale: 'en') === '5m ago'
        && Format::relative($base, $base + 7_200, RelativeTimeStyle::Long, 'en') === '2 hours ago'
        && Format::relative($base, $base + 7_200, RelativeTimeStyle::Narrow, 'en') === '2h',
    'Format::relative must support long, narrow and English wording.',
);
$october = (new DateTimeImmutable('2025-10-12 15:00:00', new DateTimeZone('UTC')))->getTimestamp();
$assert(
    Format::relative($october, $october + 60 * 86_400, timeZone: 'UTC') === '12 out.'
        && Format::relative($october, $october + 400 * 86_400, timeZone: 'UTC') === '12 out. 2025'
        && Format::relative($october, $october + 8 * 86_400, RelativeTimeStyle::Long, maxWeeks: 0, timeZone: 'UTC') === '12 de outubro'
        && Format::relative($october, $october + 60 * 86_400, locale: 'en', timeZone: 'UTC') === 'Oct 12'
        && Format::relative('2025-10-12T15:00:00Z', $october + 400 * 86_400, RelativeTimeStyle::Long, 'en', timeZone: 'UTC') === 'October 12, 2025',
    'Old times must fall back to a calendar date with the year when it differs.',
);
$assert(
    Format::bytes(512) === '512 B' && Format::bytes(1_536) === '1,5 KB' && Format::bytes(12 * 1_048_576) === '12 MB'
        && Format::bytes(1_048_576) === '1 MB' && Format::bytes(1_288_490_189, 'en') === '1.2 GB' && Format::bytes(-5) === '0 B',
    'Format::bytes must use binary units with one decimal below ten.',
);
$assert(
    Format::duration(7) === '0:07' && Format::duration(65.4) === '1:05' && Format::duration(3_723) === '1:02:03'
        && Format::duration(65, padMinutes: true) === '01:05' && Format::duration(59.7, floor: true) === '0:59' && Format::duration(-3) === '0:00',
    'Format::duration must render media clocks.',
);
$assert(
    Format::time('2026-10-14T17:05:00Z', 'America/Sao_Paulo') === '14:05' && Format::time($october, 'UTC', 'en') === '3:00 PM'
        && Format::time('', 'UTC') === '',
    'Format::time must render the clock in the given zone and locale.',
);
$assert(
    Format::relative($base, $base + 300, RelativeTimeStyle::Tiny) === '5m' && Format::relative($base, $base + 3 * 86_400, RelativeTimeStyle::Tiny) === '3d'
        && Format::relative($base, $base + 14 * 86_400, RelativeTimeStyle::Tiny) === '2sem'
        && Format::relative($base, $base + 14 * 86_400, RelativeTimeStyle::Tiny, 'en') === '2w'
        && Format::relative($base, $base + 40 * 86_400, RelativeTimeStyle::Tiny, maxDays: PHP_INT_MAX) === '40d'
        && Format::relative($october, $october + 9 * 86_400, RelativeTimeStyle::Tiny, maxWeeks: 0, timeZone: 'UTC', dateStyle: DateStyle::Medium) === '12 de out.'
        && Format::relative($base, $base + 300, timeZone: 'Not/AZone') === 'há 5 min'
        && Format::relative($october, $october + 60 * 86_400, timeZone: 'Not/AZone') === '',
    'Tiny style, day/week limits, date fallback style and invalid zones must be honoured.',
);
$assert(
    Format::date($october, DateStyle::Short, 'UTC', now: $october) === '12 out.'
        && Format::date($october, DateStyle::Medium, 'UTC', now: $october) === '12 de out.'
        && Format::date($october, DateStyle::Long, 'UTC', now: $october + 400 * 86_400) === '12 de outubro de 2025'
        && Format::date($october, DateStyle::Numeric, 'UTC') === '12/10'
        && Format::date($october, DateStyle::NumericWithYear, 'UTC') === '12/10/2025'
        && Format::date($october, DateStyle::Numeric, 'UTC', 'en') === '10/12'
        && Format::date($october, DateStyle::Medium, 'UTC', 'en', withYear: true) === 'Oct 12, 2025'
        && Format::date($october - 7 * 86_400, DateStyle::Medium, 'UTC', now: $october, padDay: true) === '05 de out.'
        && Format::date('', DateStyle::Medium) === '' && Format::time($october, 'Not/AZone') === '',
    'Format::date must render calendar dates per style and locale.',
);
$assert(
    Format::date($october, DateStyle::NumericWithShortYear, 'UTC') === '12/10/25'
        && Format::dateTime($october, timeZone: 'UTC') === '12/10/2025 15:00'
        && Format::dateTime($october, DateStyle::Medium, ', ', 'UTC', withYear: false) === '12 de out., 15:00'
        && Format::relative($october - 7 * 86_400, $october + 9 * 86_400, RelativeTimeStyle::Tiny, maxWeeks: 0, timeZone: 'UTC', dateStyle: DateStyle::Medium, padDay: true, withYear: false) === '05 de out.',
    'Short-year dates, dateTime() and the relative date fallback options must work.',
);
Format::useLocale('en-US');
$assert(Format::compact(1234) === '1.2K' && Format::locale() === 'en-US', 'useLocale() must change the default locale.');
Format::useLocale('pt-BR');
$assert(
    Text::fold('AÇÃO Ñandú Ёлка Über') === 'acao nandu елка uber' && Text::fold('Ação', lowercase: false) === 'Acao'
        && Text::fold("e\u{0301}") === 'e' && Text::fold('') === '',
    'Text::fold must remove accents and lower case.',
);
$assert(Text::foldWithMap('Ãé') === ['ae', [0, 1]] && Text::contains('Conversa com João', 'JOAO') && !Text::contains('abc', 'x') && Text::length('ação') === 4, 'Text helpers must map folded characters and search accent-insensitively.');

// OfflineMutationQueue: dedupe, backoff, persistence and drain triggers.
$queueNow = 5_000;
$queueClock = static function () use (&$queueNow): int { return $queueNow; };
$queueDisk = new MemoryPersistence();
$queue = new OfflineMutationQueue(new BackoffPolicy(baseMs: 1_000, maxMs: 4_000, maxAttempts: 4), $queueDisk, 'sync.account-1', $queueClock);
$queue->hydrate();
$like = $queue->enqueue('like:p1:1', 'post.like', ['post' => 'p1', 'liked' => true], dedupeKey: 'like:p1');
$unlike = $queue->enqueue('like:p1:2', 'post.like', ['post' => 'p1', 'liked' => false], dedupeKey: 'like:p1');
$assert($unlike->id === $like->id && $queue->pendingCount() === 1 && $queue->get($like->id)?->payload === ['post' => 'p1', 'liked' => false], 'An unsent mutation must be replaced by its dedupe key.');
$queue->sending($like->id);
$relike = $queue->enqueue('like:p1:3', 'post.like', ['post' => 'p1', 'liked' => true], dedupeKey: 'like:p1');
$assert($relike->id !== $like->id && $queue->pendingCount() === 2, 'A mutation already sending must not be replaced.');
$assert(isset($queueDisk->values['pam.sync.sync.account-1']) && str_contains($queueDisk->values['pam.sync.sync.account-1'], '"dedupeKey":"like:p1"'), 'Every change must be persisted with its dedupe key.');
$delays = [];
foreach ([1, 2, 3, 4] as $attempt) {
    $delays[] = (new BackoffPolicy(1_000, 4_000))->delayMs($attempt);
}
$jittered = (new BackoffPolicy(1_000, 10_000, jitter: 0.5))->delayMs(1, static fn (): float => 1.0);
$assert($delays === [1_000, 2_000, 4_000, 4_000] && $jittered === 1_500 && $throws(static fn () => new BackoffPolicy(jitter: 2.0)), 'BackoffPolicy must grow exponentially within its cap and jitter.');
$retry = $queue->retry($like->id, $queueNow, 'offline');
$assert($retry->status === MutationStatus::Retry && $retry->availableAtMs === $queueNow + 1_000 && $queue->nextRetryAtMs() === $queueNow + 1_000, 'retry() must apply the configured backoff.');
foreach ([2, 3] as $_) {
    $queue->sending($like->id);
    $queue->retry($like->id, $queueNow, 'offline');
}
$queue->sending($like->id);
$assert($queue->retry($like->id, $queueNow, 'offline')->status === MutationStatus::Failed, 'Exhausted retries must fail the mutation.');
$queue->sending($relike->id);
$restoredQueue = new OfflineMutationQueue(persistence: $queueDisk, name: 'sync.account-1', clock: $queueClock);
$restoredQueue->enqueue('local:only', 'note.save', ['body' => 'x']);
$restoredTotal = -1;
$restoredQueue->hydrate(static function (int $count) use (&$restoredTotal): void { $restoredTotal = $count; });
$restoredRelike = null;
foreach ($restoredQueue->all() as $mutation) {
    if ($mutation->key === 'like:p1:3') {
        $restoredRelike = $mutation;
    }
}
$assert(
    $restoredTotal === 2 && $restoredRelike?->status === MutationStatus::Retry && count($restoredQueue->all()) === 3
        && str_contains($queueDisk->values['pam.sync.sync.account-1'], 'local:only'),
    'hydrate() must merge persisted mutations, turn interrupted sends into retries and then persist the merge.',
);
$assert(OfflineMutationQueue::restore($queue->export())->get($relike->id)?->dedupeKey === 'like:p1', 'export()/restore() must keep dedupe keys.');
$tinyDisk = new MemoryPersistence(maxValueBytes: 600);
$bigQueue = new OfflineMutationQueue(persistence: $tinyDisk, name: 'big', clock: $queueClock);
$bigQueue->hydrate();
foreach (range(1, 20) as $i) {
    $bigQueue->enqueue("m:{$i}", 'message.send', ['body' => str_repeat('z', 20)]);
}
$bigSnapshot = $tinyDisk->values['pam.sync.big'];
$assert(strlen($bigSnapshot) <= 600 && str_contains($bigSnapshot, '"m:1"') && !str_contains($bigSnapshot, '"m:20"') && $bigQueue->pendingCount() === 20, 'Oversized queue snapshots must keep the oldest intents and the rest in memory.');

$writes = 0;
$failWrites = false;
$countingDisk = new CallbackPersistence(
    static function (string $key, Closure $done): void { $done(null); },
    static function (string $key, string $value, ?Closure $done, ?Closure $failed) use (&$writes, &$failWrites): void {
        ++$writes;
        if ($failWrites) {
            $failed?->__invoke(new RuntimeException('disk full'));

            return;
        }
        $done?->__invoke();
    },
);
$acked = [];
$early = new OfflineMutationQueue(persistence: $countingDisk, name: 'acks', clock: $queueClock);
$early->enqueue('vote:1', 'poll.vote', ['option' => 1], stored: static function (\Pam\Native\Sync\Mutation $m) use (&$acked): void { $acked[] = "stored:{$m->key}"; });
$assert($acked === [] && $writes === 0, 'Before hydrate() nothing is written and stored callbacks wait.');
$early->hydrate();
$assert($acked === ['stored:vote:1'] && $writes === 1, 'hydrate() must write once and acknowledge waiting enqueues.');
$updated = $early->enqueue('vote:1', 'poll.vote', ['option' => 2]);
$assert($updated->payload === ['option' => 2] && count($early->all()) === 1 && $updated->createdAtMs === $queueNow, 'Re-enqueueing an unsent key must update its payload.');
$failWrites = true;
$early->enqueue('vote:2', 'poll.vote', ['option' => 3], failed: static function (Throwable $error, \Pam\Native\Sync\Mutation $m) use (&$acked): void { $acked[] = "failed:{$m->key}:{$error->getMessage()}"; });
$assert(end($acked) === 'failed:vote:2:disk full', 'A failed write must reach the failed callback.');
$failWrites = false;
$before = $writes;
$early->batch(static function (OfflineMutationQueue $q): void {
    foreach ($q->ready() as $m) {
        $q->sending($m->id);
    }
});
$assert($writes === $before + 1 && count($early->all('poll.vote')) === 2 && $early->all('other') === [], 'batch() must write once; all() must filter by operation.');
$early->clear();
$assert($early->all() === [] && $writes === $before + 2, 'clear() must drop and persist everything.');
$assert(!$throws(static fn () => $early->enqueue('sha256/abc+def=', 'x.y', [])) && $throws(static fn () => $early->enqueue('has space', 'x.y', [])), 'Idempotency keys accept printable ASCII only.');

$drains = [];
$triggered = new OfflineMutationQueue(clock: $queueClock);
$drainSubscription = $triggered->drainOn(static function (OfflineMutationQueue $q) use (&$drains): void { $drains[] = count($q->ready()); }, DrainTrigger::Enqueue, DrainTrigger::Foreground, DrainTrigger::Connectivity, DrainTrigger::RetryDue);
$assert($throws(static fn () => $triggered->drainOn(static fn () => null)), 'drainOn() needs at least one trigger.');
$sent = $triggered->enqueue('msg:1', 'message.send', ['body' => 'oi']);
$assert($drains === [1], 'Enqueue must drain at once.');
$triggered->sending($sent->id);
$triggered->retry($sent->id, $queueNow, 'timeout');
$retryTimer = $armed();
$queueNow += 1_000;
$fire($retryTimer);
$assert($drains === [1, 1], 'A due retry must drain.');
Runtime::dispatchEvent(0, \Pam\Native\EventKind::AppState->value, (string) AppState::Background->value);
Runtime::dispatchEvent(0, \Pam\Native\EventKind::AppState->value, (string) AppState::Active->value);
$assert($drains === [1, 1, 1], 'Returning to the foreground must drain.');
$triggered->online(false);
$triggered->online(true);
$assert($drains === [1, 1, 1, 1], 'Connectivity coming back must drain.');
$triggered->sending($sent->id);
$triggered->applied($sent->id);
$triggered->online(false);
$triggered->online(true);
$assert($drains === [1, 1, 1, 1] && !$triggered->drain(), 'Nothing ready means no drain.');
$drainSubscription->cancel();
$triggered->enqueue('msg:2', 'message.send', ['body' => 'tchau']);
$assert($drains === [1, 1, 1, 1] && $triggered->prune() === 1, 'A cancelled drain subscription must stop draining.');
