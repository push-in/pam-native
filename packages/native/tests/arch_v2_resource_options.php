<?php

declare(strict_types=1);

use Pam\Native\AsyncStatus;
use Pam\Native\Composables\Resource;
use Pam\Native\Scheduling\Scheduler;

use function Pam\Native\signal;
use function Pam\Native\useResource;
use function Pam\Native\watch;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

Scheduler::reset();
Resource::forgetCache();
$drain = static fn () => Scheduler::drain(100);

// initial: shown at once (stale), replaced by the first load.
$seeded = useResource(static fn (): array => ['name' => 'Bia'], initial: ['name' => 'Ana']);
$assert($seeded->data === ['name' => 'Ana'] && $seeded->stale && $seeded->status === AsyncStatus::Stale && $seeded->refreshing && !$seeded->loading, 'initial: must show its data at once as stale.');
$drain();
$assert($seeded->data === ['name' => 'Bia'] && !$seeded->stale, 'initial: must be replaced by the first load.');
$page = useResource(static fn (?string $cursor): array => ['data' => [['id' => 3]], 'next' => null], paginate: true, initial: ['data' => [['id' => 1], ['id' => 2]], 'next' => 'c2']);
$assert($page->data === [['id' => 1], ['id' => 2]] && $page->hasMore, 'initial: on a paginated resource must read items and cursors like a page.');
$drain();
$assert($page->data === [['id' => 3]] && !$page->hasMore, 'initial: on a paginated resource must be replaced by the first page.');

// cache store adapter: reads and writes go to the app's own cache, not to the framework's.
$appCache = ['feed' => ['data' => [['id' => 9]], 'next' => null, 'previous' => null]];
$adapter = static function (string $id, ?array $entry = null) use (&$appCache): ?array {
    if (func_num_args() === 1) {
        return $appCache[$id] ?? null;
    }
    $appCache[$id] = $entry;

    return null;
};
$adapted = useResource(static fn (?string $cursor): array => ['data' => [['id' => 10]]], paginate: true, cache: ['key' => 'feed', 'store' => $adapter]);
$assert($adapted->data === [['id' => 9]] && $adapted->stale, 'cache store: must show the adapter entry at once.');
$drain();
$assert($appCache['feed']['data'] === [['id' => 10]] && Resource::store()->get('feed') === null, 'cache store: must write to the adapter only.');
$threw = false;
try {
    useResource(static fn () => 1, cache: ['key' => 'x', 'store' => 'not callable']);
} catch (InvalidArgumentException) {
    $threw = true;
}
$assert($threw, 'cache store: a non-callable store must be rejected.');

// onSettled / onPage.
$settled = [];
$pages = [];
$fail = false;
$events = useResource(static function (?string $cursor) use (&$fail): array {
    if ($fail) {
        throw new RuntimeException('offline');
    }

    return $cursor === null ? ['data' => [['id' => 1]], 'next' => 'c1'] : ['data' => [['id' => 2]], 'next' => null];
}, paginate: true);
$events->onSettled(static function (mixed $data, ?string $error) use (&$settled): void {
    $settled[] = [count($data), $error];
});
$off = $events->onPage(static function (mixed $page, string $direction) use (&$pages): void {
    $pages[] = [$direction, $page['next']];
});
$drain();
$events->more();
$drain();
$assert($pages === [['initial', 'c1'], ['older', null]] && $settled === [[1, null]], 'onPage must fire per page and onSettled per first load, got '.json_encode([$pages, $settled]).'.');
$fail = true;
$off();
$events->reload();
$drain();
$assert($settled === [[1, null], [2, 'offline']] && count($pages) === 2, 'onSettled must report failures; an unsubscribed onPage must not fire.');

// manual: never loads from construction, reads or key changes; only load()/reload().
$calls = 0;
$query = signal('a');
$manual = useResource(static function () use (&$calls, $query): string {
    $calls++;

    return 'r-'.$query->peek();
}, manual: true, key: static fn (): string => $query->get());
$reads = watch(static fn () => $manual->data, static function (): void {});
$drain();
$assert($calls === 0 && $manual->data === null && !$manual->loading, 'manual: must not load on construction or read.');
$manual->load();
$drain();
$assert($calls === 1 && $manual->data === 'r-a', 'manual: load() must fetch.');
$manual->load();
$drain();
$assert($calls === 1, 'manual: load() once started is a no-op.');
$query->set('b');
$drain();
$assert($calls === 1 && $manual->data === null, 'manual: a key change must reset without fetching.');
$manual->reload();
$drain();
$assert($calls === 2 && $manual->data === 'r-b', 'manual: reload() must fetch the new key.');
$reads->dispose();
foreach ([$seeded, $page, $adapted, $events, $manual] as $resource) {
    $resource->dispose();
}
Resource::forgetCache();
