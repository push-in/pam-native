<?php

declare(strict_types=1);

use Pam\Native\AppState;
use Pam\Native\Async\Promise;
use Pam\Native\Async\PromiseState;
use Pam\Native\Cache\MemoryPersistence;
use Pam\Native\Composables\Resource;
use Pam\Native\Composables\ResourcePage;
use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;
use Pam\Native\Scheduling\CancellationToken;
use Pam\Native\Scheduling\Scheduler;

use function Pam\Native\signal;
use function Pam\Native\useResource;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

Scheduler::reset();
Resource::forgetCache();
$disk = new MemoryPersistence();
Resource::usePersistence($disk);
$drain = static fn () => Scheduler::drain(100);
$ids = static fn (Resource $r): array => array_map(static fn (array $item) => $item['id'] ?? $item['client_message_id'], $r->data);

// 1. Persistent cache: offline-first on cold start, scoped per account, bounded.
$online = true;
$feedLoader = static function (?string $cursor) use (&$online): array {
    if (!$online) throw new RuntimeException('offline');
    $items = [];
    for ($i = 1; $i <= 150; $i++) $items[] = ['id' => $i];

    return ['data' => $items, 'next' => 'c2'];
};
$feed = useResource($feedLoader, paginate: true, cache: ['key' => 'feed', 'scope' => 42, 'persist' => true]);
$drain();
$assert(count($feed->data) === 150 && $feed->hasMore, 'The persistent resource loads normally.');
Runtime::dispatchEvent(0, EventKind::AppState->value, (string) AppState::Background->value);
Runtime::dispatchEvent(0, EventKind::AppState->value, (string) AppState::Active->value);
$assert(count($disk->values) === 1 && str_contains((string) array_key_first($disk->values), '42'), 'Going to the background writes the scoped cache: '.json_encode(array_keys($disk->values)));

Resource::usePersistence($disk); // cold start: memory gone, disk kept
Resource::forgetCache();
$online = false;
$cold = useResource($feedLoader, paginate: true, cache: ['key' => 'feed', 'scope' => 42, 'persist' => true]);
$assert($cold->stale && count($cold->data) === 100 && !$cold->hasMore && $cold->refreshing && !$cold->loading, 'Cold start shows the persisted first 100 items at once (no cursor past the cut).');
$drain();
$assert($cold->error === 'offline' && count($cold->data) === 100, 'Offline, the cached data stays with the error.');
$other = useResource($feedLoader, paginate: true, cache: ['key' => 'feed', 'scope' => 43, 'persist' => true]);
$assert($other->data === [] && $other->loading, 'Another account never sees this cache.');
$drain();
Resource::forgetCache(scope: 42);
Resource::usePersistence($disk);
$afterLogout = useResource($feedLoader, paginate: true, cache: ['key' => 'feed', 'scope' => 42, 'persist' => true]);
$assert($afterLogout->data === [] && !$afterLogout->stale, 'forgetCache(scope:) clears that account on disk too.');
$drain();
$online = true;

// 2. Reactive dependencies: a change cancels, swaps the cache and reloads.
$user = signal(1);
$query = signal('a');
$pending = [];
$asked = [];
$search = useResource(static function (CancellationToken $token, ResourcePage $page) use (&$pending, &$asked): Promise {
    $asked[] = $page->key;
    $promise = new Promise();
    $pending[] = [$promise, $page->key];

    return $promise;
}, key: static fn (): array => [$user->value, $query->value], cache: 'search');
$drain();
[$first] = $pending[0];
$first->resolve(['q' => 'a']);
$assert($search->data === ['q' => 'a'] && $asked === [[1, 'a']], 'Dependencies reach the loader as $page->key.');
$query->value = 'b';
$assert($search->data === null && $search->loading, 'A new key without cache starts blank.');
$drain();
[$inFlight] = $pending[1];
$query->value = 'c';
$drain();
$assert($inFlight->state() === PromiseState::Cancelled && $asked === [[1, 'a'], [1, 'b'], [1, 'c']], 'A key change cancels the request in flight.');
$pending[2][0]->resolve(['q' => 'c']);
$query->value = 'a';
$assert($search->data === ['q' => 'a'] && $search->stale, 'Going back to a key shows its cached result at once.');
$user->value = 2;
$assert($search->data === null, 'Another account (key) does not reuse the cache of the previous one.');
$search->dispose();

// 3. Races: reload() discards an older-page request in flight; new params restart; the last request wins.
$requests = [];
$race = useResource(static function (?string $cursor, CancellationToken $token, ResourcePage $page) use (&$requests): Promise {
    $promise = new Promise();
    $requests[] = [$promise, $page->direction, $page->around];

    return $promise;
}, paginate: true);
$drain();
$requests[0][0]->resolve(['data' => [['id' => 1], ['id' => 2]], 'next' => 'c2']);
$race->more();
$drain();
[$morePromise] = $requests[1];
$race->reload();
$drain();
$assert($morePromise->state() === PromiseState::Cancelled && !$race->loadingMore && $race->refreshing, 'reload() must cancel the more() in flight.');
$race->reload();
$drain();
$assert(count($requests) === 3, 'reload() with the same params joins the one in flight.');
$race->reload(around: 50);
$drain();
$assert(count($requests) === 4 && $requests[2][0]->state() === PromiseState::Cancelled && $requests[3][2] === 50, 'reload() with new params restarts.');
$requests[3][0]->resolve(['data' => [['id' => 49], ['id' => 50], ['id' => 51]], 'next' => 'o1', 'prev' => 'n1']);
$assert($ids($race) === [49, 50, 51] && $race->hasMore && $race->hasNewer, 'The last request wins.');

// 4. Both directions around an item.
$race->more('newer');
$race->more('older');
$drain();
$directions = array_map(static fn (array $r): string => $r[1], array_slice($requests, 4));
$assert($directions === ['newer', 'older'] && $race->loadingNewer && $race->loadingMore, 'more(newer) and more(older) run side by side: '.json_encode($directions));
$requests[5][0]->resolve(['data' => [['id' => 52]], 'next' => null]);
$requests[4][0]->resolve(['data' => [['id' => 47], ['id' => 48], ['id' => 49]], 'prev' => null]);
$assert($ids($race) === [47, 48, 49, 50, 51, 52] && !$race->hasMore && !$race->hasNewer, 'Newer pages prepend, older pages append: '.json_encode($ids($race)));
$badDirection = false;
try {
    $race->more('sideways');
} catch (InvalidArgumentException) {
    $badDirection = true;
}
$assert($badDirection, 'Unknown directions are rejected.');
$race->dispose();

// 5. Identity: several fields reconcile the optimistic item with the server's; or a closure.
$chatPages = [];
$chat = useResource(static function (?string $cursor) use (&$chatPages): Promise {
    $promise = new Promise();
    $chatPages[] = $promise;

    return $promise;
}, paginate: true, identity: ['id', 'client_message_id']);
$drain();
$chatPages[0]->resolve(['data' => [['id' => 9, 'client_message_id' => 'c0', 'text' => 'old']], 'prev' => 'n']);
$chat->prepend(['client_message_id' => 'c1', 'text' => 'sending']);
$chat->more('newer');
$drain();
$chatPages[1]->resolve(['data' => [['id' => 10, 'client_message_id' => 'c1', 'text' => 'sent']], 'prev' => null]);
$assert(
    count($chat->data) === 2 && $chat->data[0] === ['id' => 10, 'client_message_id' => 'c1', 'text' => 'sent'],
    'A server item with the optimistic client_message_id replaces it: '.json_encode($chat->data),
);
$chat->replace('c1', ['id' => 10, 'client_message_id' => 'c1', 'text' => 'sent']);
$chat->markFailed(9);
$assert(count($chat->data) === 2 && $chat->isFailed(['id' => 9]) && $chat->data[1]['_failed'] === true, 'Keys match any identity field.');
$chat->dispose();

$named = useResource(static fn (): array => [['name' => 'Ana']], paginate: true, identity: static fn (mixed $a, mixed $b): bool => is_array($a) && strtolower($a['name'] ?? '') === strtolower(is_array($b) ? ($b['name'] ?? '') : (string) $b));
$drain();
$named->replace('ANA', ['name' => 'ana', 'v' => 2]);
$assert($named->data === [['name' => 'ana', 'v' => 2]], 'A closure identity matches items its own way.');
$named->dispose();

Resource::usePersistence(null);
Resource::forgetCache();
Scheduler::reset();

echo "ARCH_V2_RESOURCE_PRODUCTION_OK persist scope cold-start deps race bidirectional identity\n";
