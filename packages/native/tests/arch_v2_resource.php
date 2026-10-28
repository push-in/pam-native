<?php

declare(strict_types=1);

use Pam\Native\AsyncStatus;
use Pam\Native\Composables\Resource;
use Pam\Native\Scheduling\Scheduler;
use Pam\Native\Signals\EffectScope;

use function Pam\Native\effectScope;
use function Pam\Native\useResource;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

Scheduler::reset();
Resource::forgetCache();
$drain = static fn () => Scheduler::drain(100);

// Plain resource: loading, then data, off the calling stack.
$calls = 0;
$profile = useResource(static function () use (&$calls): array {
    $calls++;

    return ['name' => 'Ana'];
});
$assert($profile->loading && $profile->data === null && $calls === 0, 'useResource() must start loading without running the loader inline.');
$profile->reload();
$drain();
$assert(!$profile->loading && $profile->data === ['name' => 'Ana'] && $calls === 1 && $profile->status === AsyncStatus::Content, 'useResource() must expose the loaded data; repeated reloads in flight are joined.');

// Errors keep the data and expose the message.
$fail = false;
$flaky = useResource(static function () use (&$fail): array {
    if ($fail) {
        throw new RuntimeException('Sem conexão');
    }

    return ['ok'];
});
$drain();
$fail = true;
$flaky->reload();
$assert($flaky->refreshing && !$flaky->loading, 'A reload over data must be a refresh, not a blank loading state.');
$drain();
$assert($flaky->error === 'Sem conexão' && $flaky->data === ['ok'] && $flaky->status === AsyncStatus::Error, 'Errors must keep the previous data.');

// Pagination with cursors, joined more() calls and duplicate suppression.
$pages = [
    '' => ['data' => [['id' => 1], ['id' => 2]], 'next' => 'c2'],
    'c2' => ['data' => [['id' => 2], ['id' => 3]], 'meta' => ['next_cursor' => 'c3']],
    'c3' => ['items' => [['id' => 4]], 'next_cursor' => null],
];
$requested = [];
$feed = useResource(static function (?string $cursor) use ($pages, &$requested): array {
    $requested[] = $cursor;

    return $pages[$cursor ?? ''];
}, paginate: true, cache: 'feed');
$drain();
$feed->more();
$feed->more();
$drain();
$feed->more();
$drain();
$feed->more();
$drain();
$assert(
    $requested === [null, 'c2', 'c3'] && array_column($feed->data, 'id') === [1, 2, 3, 4] && !$feed->hasMore && !$feed->loadingMore,
    'Pagination must follow cursors, join repeated more() calls and skip duplicates: '.json_encode($requested),
);

// Optimistic helpers.
$feed->prepend(['id' => 'tmp-1', 'text' => 'sending']);
$feed->append(['id' => 'tmp-2']);
$feed->markFailed('tmp-2');
$assert($feed->data[0]['id'] === 'tmp-1' && $feed->isFailed(['id' => 'tmp-2']) && $feed->data[count($feed->data) - 1]['_failed'] === true, 'prepend/append/markFailed must update the list at once.');
$feed->replace('tmp-1', ['id' => 99, 'text' => 'sent']);
$feed->remove('tmp-2');
$feed->remove(['id' => 4]);
$assert(array_column($feed->data, 'id') === [99, 1, 2, 3] && !$feed->isFailed('tmp-2'), 'replace/remove must swap and drop items by key or value.');

// Cache: a new resource with the same key shows the last result at once and refreshes behind it.
$cachedLoads = 0;
$again = useResource(static function (?string $cursor) use (&$cachedLoads): array {
    $cachedLoads++;

    return ['data' => [['id' => 7]], 'next' => null];
}, paginate: true, cache: 'feed');
$assert($again->stale && array_column($again->data, 'id') === [99, 1, 2, 3] && $again->refreshing && !$again->loading, 'A cached resource must render the last result immediately.');
$drain();
$assert(!$again->stale && array_column($again->data, 'id') === [7] && $cachedLoads === 1, 'The cached result must be refreshed behind the scenes.');

// Lazy: nothing runs until something reads it.
$lazyLoads = 0;
$lazy = useResource(static function () use (&$lazyLoads): string {
    $lazyLoads++;

    return 'lazy';
}, lazy: true);
$drain();
$assert($lazyLoads === 0, 'A lazy resource must not load before it is read.');
$assert($lazy->loading, 'Reading a lazy resource starts it.');
$drain();
$assert($lazy->data === 'lazy' && $lazyLoads === 1, 'A lazy resource must load once read.');

// Thenable loaders (asynchronous HTTP) settle later.
$pending = new class {
    public ?Closure $resolve = null;

    public function then(Closure $fulfilled, ?Closure $rejected = null): self
    {
        $this->resolve = $fulfilled;

        return $this;
    }
};
$async = useResource(static fn (): object => $pending);
$drain();
$assert($async->loading && $pending->resolve !== null, 'A thenable result keeps the resource loading.');
($pending->resolve)(['async' => true]);
$assert(!$async->loading && $async->data === ['async' => true], 'A thenable must settle the resource.');

// Disposal cancels in-flight work and ignores late results (no leak, no stale writes).
$ran = 0;
$late = null;
$scope = effectScope(static function () use (&$ran, &$late, $pending): void {
    $late = useResource(static function () use (&$ran): array {
        $ran++;

        return ['late'];
    });
});
$scope->dispose();
$drain();
$assert($ran === 0 && $late->disposed() && $late->data === null && Scheduler::pending() === 0, 'Disposing the owning scope must cancel the request.');

// refreshOnFocus: the first focus is the mount, later focuses reload.
$focusLoads = 0;
$focusScope = new EffectScope();
$focused = $focusScope->run(static function () use (&$focusLoads): Resource {
    return useResource(static function () use (&$focusLoads): int {
        return ++$focusLoads;
    }, refreshOnFocus: true);
});
$drain();
$focusScope->mount();
$focusScope->focusChanged(true);
$drain();
$focusScope->focusChanged(false);
$focusScope->focusChanged(true);
$drain();
$assert($focusLoads === 2 && $focused->data === 2, 'refreshOnFocus must reload when the screen is focused again.');
$focusScope->dispose();
Resource::forgetCache();
Scheduler::reset();

echo "ARCH_V2_RESOURCE_OK load error paginate optimistic cache lazy thenable cancel focus\n";
