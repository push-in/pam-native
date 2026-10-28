<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Closure;
use Pam\Native\AsyncResource;
use Pam\Native\AsyncStatus;
use Pam\Native\AsyncValue;
use Pam\Native\Cache\BoundedStore;
use Pam\Native\Disposable;
use Pam\Native\Scheduling\CancellationToken;
use Pam\Native\Scheduling\TaskPriority;
use Pam\Native\Signals\EffectScope;
use Pam\Native\Signals\Signal;
use Throwable;

use function array_filter;
use function array_is_list;
use function array_key_exists;
use function array_map;
use function array_merge;
use function array_values;
use function is_array;
use function is_int;
use function is_object;
use function is_string;
use function method_exists;
use function property_exists;

/**
 * `useResource()`: data loaded through the scheduler, with pagination,
 * cache, focus refresh and optimistic updates.
 *
 * ```php
 * $feed = useResource(fn (?string $cursor) => Api::client()->get('/feed', ['cursor' => $cursor])->json(),
 *     paginate: true, refreshOnFocus: true, cache: 'feed');
 * ```
 *
 * - Requests run on the cooperative Scheduler (never inside the event that
 *   asked for them), are cancelled when the owning scope is disposed, and
 *   repeated reload()/more() calls while one is in flight are joined.
 * - With `cache`, the last result shows at once and refreshes behind it.
 * - With `paginate`, the loader receives the cursor (null for the first
 *   page) and returns `['data' => [...], 'next' => ?string]` (also accepted:
 *   `items`, `next_cursor`, `nextCursor`, `meta.next_cursor`); `data` is the
 *   accumulated list and more() loads the next page.
 * - A loader may also return a thenable (`then(onFulfilled, onRejected)`),
 *   such as an asynchronous HTTP request.
 *
 * @property-read mixed $data the result (the accumulated list when paginated)
 * @property-read bool $loading the first load or a reload without data is running
 * @property-read bool $refreshing a reload is running while data is shown
 * @property-read bool $loadingMore more() is running
 * @property-read string|null $error message of the last failure
 * @property-read bool $hasMore another page is available
 * @property-read bool $stale the data came from the cache and is being refreshed
 * @property-read AsyncStatus $status
 * @property-read AsyncValue $value the state for <Suspense>
 */
final class Resource extends ReactiveObject implements Disposable
{
    private const int CACHE_ITEMS = 64;
    private const int CACHE_BYTES = 4_194_304;

    private static ?BoundedStore $store = null;

    /** @var Signal<array{data: mixed, loading: bool, refreshing: bool, loadingMore: bool, error: ?string, next: ?string, hasMore: bool, stale: bool, status: AsyncStatus}> */
    private Signal $state;
    private AsyncResource $first;
    private AsyncResource $next;
    /** @var list<Closure(): void> */
    private array $unsubscribe = [];
    private int $generation = 0;
    private bool $started = false;
    private bool $disposed = false;
    private int $focusCount = 0;
    /** @var array<string, true> keys of items marked failed */
    private array $failed = [];

    /**
     * @param Closure(mixed...): mixed $loader `fn (?string $cursor, CancellationToken $token)` when paginated, else `fn (CancellationToken $token)`
     * @param string $key item field used by replace()/remove()/markFailed() to find an item
     * @param (Closure(mixed): ?string)|null $nextCursor reads the next cursor from a page (paginate)
     * @param (Closure(mixed): list<mixed>)|null $items reads the items from a page (paginate)
     */
    public function __construct(
        private readonly Closure $loader,
        private readonly bool $paginate = false,
        bool $refreshOnFocus = false,
        private readonly bool $lazy = false,
        private readonly ?string $cache = null,
        private readonly string $key = 'id',
        private readonly ?Closure $nextCursor = null,
        private readonly ?Closure $items = null,
    ) {
        $cached = $cache !== null ? self::store()->get($cache) : null;
        $hasCache = is_array($cached) && array_key_exists('data', $cached);
        $this->state = new Signal([
            'data' => $hasCache ? $cached['data'] : ($paginate ? [] : null),
            'loading' => false,
            'refreshing' => false,
            'loadingMore' => false,
            'error' => null,
            'next' => $hasCache && is_string($cached['next'] ?? null) ? $cached['next'] : null,
            'hasMore' => $hasCache ? is_string($cached['next'] ?? null) : false,
            'stale' => $hasCache,
            'status' => $hasCache ? AsyncStatus::Stale : AsyncStatus::Loading,
        ]);
        $priorityKey = $cache !== null ? 'resource:'.$cache : null;
        $this->first = new AsyncResource(fn (CancellationToken $token): mixed => $this->call(null, $token), $priorityKey);
        $this->next = new AsyncResource(
            fn (CancellationToken $token): mixed => $this->call($this->peek('next'), $token),
            $priorityKey !== null ? $priorityKey.':more' : null,
        );
        $this->unsubscribe[] = $this->first->subscribe(fn (AsyncValue $value) => $this->settled($value, false, $this->generation));
        $this->unsubscribe[] = $this->next->subscribe(fn (AsyncValue $value) => $this->settled($value, true, $this->generation));

        $scope = EffectScope::current();
        $scope?->own($this);
        if ($refreshOnFocus && $scope !== null) {
            $scope->onFocus(function (): void {
                // The first focus is the mount itself: the initial load covers it.
                if ($this->focusCount++ > 0 && $this->started) {
                    $this->reload();
                }
            });
        }
        if (!$lazy) {
            $this->start();
        }
    }

    protected function properties(): array
    {
        return ['data', 'loading', 'refreshing', 'loadingMore', 'error', 'hasMore', 'stale', 'status', 'value'];
    }

    protected function read(string $name): mixed
    {
        if ($this->lazy && !$this->started) {
            // Lazy resources start when something first renders them.
            $this->start();
        }
        $state = $this->state->get();

        return match ($name) {
            'value' => match (true) {
                $state['error'] !== null => AsyncValue::error($state['error'], previous: $state['data']),
                $state['loading'] => AsyncValue::loading($state['data']),
                $state['data'] === null || $state['data'] === [] => AsyncValue::empty(),
                $state['stale'] => AsyncValue::stale($state['data']),
                default => AsyncValue::content($state['data']),
            },
            default => $state[$name],
        };
    }

    /** Loads the first page again (joined with a reload already in flight). */
    public function reload(): void
    {
        if ($this->disposed) {
            return;
        }
        $this->started = true;
        $state = $this->state->peek();
        if ($state['loading'] || $state['refreshing']) {
            return;
        }
        $hasData = $state['data'] !== null && $state['data'] !== [];
        $this->patch(['loading' => !$hasData, 'refreshing' => $hasData, 'error' => null]);
        $this->first->load(TaskPriority::UserBlocking);
    }

    /** Loads the next page (paginate); a no-op while loading or at the end. */
    public function more(): void
    {
        if ($this->disposed || !$this->paginate) {
            return;
        }
        $state = $this->state->peek();
        if (!$state['hasMore'] || $state['loading'] || $state['loadingMore'] || $state['refreshing']) {
            return;
        }
        $this->patch(['loadingMore' => true, 'error' => null]);
        $this->next->load(TaskPriority::Background);
    }

    /** Optimistic insert at the start of the list. */
    public function prepend(mixed $item): void
    {
        $this->mutate(static fn (array $items): array => [$item, ...$items]);
    }

    /** Optimistic insert at the end of the list. */
    public function append(mixed $item): void
    {
        $this->mutate(static fn (array $items): array => [...$items, $item]);
    }

    /** Swaps the optimistic $temporary (item or key) for the server's $real item. */
    public function replace(mixed $temporary, mixed $real): void
    {
        $this->unfail($temporary);
        $this->mutate(fn (array $items): array => array_map(
            fn (mixed $item): mixed => $this->matches($item, $temporary) ? $real : $item,
            $items,
        ));
    }

    /** Flags an optimistic item whose request failed (`item._failed` in templates; isFailed()). */
    public function markFailed(mixed $temporary): void
    {
        $key = $this->targetKey($temporary);
        if ($key !== null) {
            $this->failed[$key] = true;
        }
        $this->mutate(fn (array $items): array => array_map(
            fn (mixed $item): mixed => is_array($item) && $this->matches($item, $temporary) ? [...$item, '_failed' => true] : $item,
            $items,
        ));
    }

    public function isFailed(mixed $item): bool
    {
        $key = $this->targetKey($item);

        return ($key !== null && isset($this->failed[$key])) || (is_array($item) && ($item['_failed'] ?? false) === true);
    }

    /** Removes an item (or the item with that key). */
    public function remove(mixed $target): void
    {
        $this->unfail($target);
        $this->mutate(fn (array $items): array => array_values(array_filter(
            $items,
            fn (mixed $item): bool => !$this->matches($item, $target),
        )));
    }

    /**
     * Rewrites the data in place (optimistic edit of any shape).
     *
     * @param Closure(mixed): mixed $update
     */
    public function update(Closure $update): void
    {
        $this->patch(['data' => $update($this->state->peek()['data'])]);
        $this->remember();
    }

    public function dispose(): void
    {
        if ($this->disposed) {
            return;
        }
        $this->disposed = true;
        $this->generation++;
        $this->first->cancel();
        $this->next->cancel();
        foreach ($this->unsubscribe as $unsubscribe) {
            $unsubscribe();
        }
        $this->unsubscribe = [];
    }

    public function disposed(): bool
    {
        return $this->disposed;
    }

    /** @internal Shared result cache (also used by tests). */
    public static function store(): BoundedStore
    {
        return self::$store ??= new BoundedStore('pam-resources', self::CACHE_ITEMS, maxBytes: self::CACHE_BYTES);
    }

    /** Forgets one cached result, or all of them (e.g. on logout). */
    public static function forgetCache(?string $key = null): void
    {
        $key === null ? self::store()->clear() : self::store()->forget($key);
    }

    private function start(): void
    {
        if ($this->started) {
            return;
        }
        $this->reload();
    }

    private function call(?string $cursor, CancellationToken $token): mixed
    {
        $result = $this->paginate ? ($this->loader)($cursor, $token) : ($this->loader)($token);
        if (is_object($result) && method_exists($result, 'then')) {
            $generation = $this->generation;
            $more = $cursor !== null;
            $result->then(
                function (mixed $value) use ($generation, $more): void {
                    $this->settled(AsyncValue::content($value), $more, $generation, true);
                },
                function (mixed $error) use ($generation, $more): void {
                    $message = $error instanceof Throwable ? $error->getMessage() : (is_string($error) ? $error : 'Request failed.');
                    $this->settled(AsyncValue::error($message), $more, $generation, true);
                },
            );

            return ResourcePending::instance();
        }

        return $result;
    }

    private function settled(AsyncValue $value, bool $more, int $generation, bool $async = false): void
    {
        if ($this->disposed || $generation !== $this->generation || $value->status === AsyncStatus::Loading) {
            return;
        }
        if (!$async && $value->data instanceof ResourcePending) {
            return;
        }
        $flags = $more ? ['loadingMore' => false] : ['loading' => false, 'refreshing' => false];
        if ($value->status === AsyncStatus::Error) {
            $this->patch([...$flags, 'error' => $value->message ?? 'Request failed.', 'status' => AsyncStatus::Error]);

            return;
        }
        $page = $value->status === AsyncStatus::Empty ? ($this->paginate ? [] : null) : $value->data;
        if (!$this->paginate) {
            $this->patch([...$flags, 'data' => $page, 'error' => null, 'stale' => false, 'status' => $value->status]);
            $this->remember();

            return;
        }
        $items = $this->itemsOf($page);
        $next = $this->cursorOf($page);
        $data = $more ? $this->merged($this->state->peek()['data'], $items) : $items;
        $this->patch([
            ...$flags,
            'data' => $data,
            'next' => $next,
            'hasMore' => $next !== null,
            'error' => null,
            'stale' => false,
            'status' => $data === [] ? AsyncStatus::Empty : AsyncStatus::Content,
        ]);
        $this->remember();
    }

    /** @return list<mixed> */
    private function itemsOf(mixed $page): array
    {
        if ($this->items !== null) {
            return array_values(($this->items)($page));
        }
        if (!is_array($page)) {
            return [];
        }
        if (array_is_list($page)) {
            return $page;
        }
        $items = $page['data'] ?? $page['items'] ?? [];

        return is_array($items) ? array_values($items) : [];
    }

    private function cursorOf(mixed $page): ?string
    {
        if ($this->nextCursor !== null) {
            return ($this->nextCursor)($page);
        }
        if (!is_array($page) || array_is_list($page)) {
            return null;
        }
        $meta = is_array($page['meta'] ?? null) ? $page['meta'] : [];
        $cursor = $page['next'] ?? $page['next_cursor'] ?? $page['nextCursor'] ?? $meta['next_cursor'] ?? $meta['nextCursor'] ?? null;

        return is_string($cursor) && $cursor !== '' ? $cursor : (is_int($cursor) ? (string) $cursor : null);
    }

    /**
     * Next page appended, skipping items already listed (shifting cursors).
     *
     * @param list<mixed> $items
     * @return list<mixed>
     */
    private function merged(mixed $current, array $items): array
    {
        $current = is_array($current) ? array_values($current) : [];
        $seen = [];
        foreach ($current as $item) {
            $key = $this->keyOf($item);
            if ($key !== null) {
                $seen[$key] = true;
            }
        }

        return array_merge($current, array_values(array_filter(
            $items,
            fn (mixed $item): bool => ($key = $this->keyOf($item)) === null || !isset($seen[$key]),
        )));
    }

    /** @param Closure(list<mixed>): list<mixed> $change */
    private function mutate(Closure $change): void
    {
        $data = $this->state->peek()['data'];
        $items = is_array($data) ? array_values($data) : [];
        $items = $change($items);
        $this->patch([
            'data' => $items,
            'status' => $items === [] ? AsyncStatus::Empty : AsyncStatus::Content,
        ]);
        $this->remember();
    }

    private function remember(): void
    {
        if ($this->cache === null) {
            return;
        }
        $state = $this->state->peek();
        self::store()->put($this->cache, ['data' => $state['data'], 'next' => $state['next']]);
    }

    /** @param array<string, mixed> $changes */
    private function patch(array $changes): void
    {
        $this->state->set([...$this->state->peek(), ...$changes]);
    }

    private function peek(string $field): mixed
    {
        return $this->state->peek()[$field];
    }

    private function matches(mixed $item, mixed $target): bool
    {
        if ($item === $target) {
            return true;
        }
        $key = $this->keyOf($item);

        return $key !== null && $key === $this->targetKey($target);
    }

    /** Key of an item, or the target itself when it is a key. */
    private function targetKey(mixed $target): ?string
    {
        return is_array($target) || is_object($target)
            ? $this->keyOf($target)
            : (is_int($target) || is_string($target) ? (string) $target : null);
    }

    private function unfail(mixed $target): void
    {
        $key = $this->targetKey($target);
        if ($key !== null) {
            unset($this->failed[$key]);
        }
    }

    private function keyOf(mixed $item): ?string
    {
        $value = match (true) {
            is_array($item) => $item[$this->key] ?? null,
            is_object($item) && property_exists($item, $this->key) => $item->{$this->key},
            default => null,
        };

        return is_int($value) || is_string($value) ? (string) $value : null;
    }
}

