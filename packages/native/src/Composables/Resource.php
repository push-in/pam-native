<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Closure;
use InvalidArgumentException;
use Pam\Native\App;
use Pam\Native\AppState;
use Pam\Native\AsyncStatus;
use Pam\Native\AsyncValue;
use Pam\Native\Cache\BoundedStore;
use Pam\Native\Cache\CachePersistence;
use Pam\Native\Cache\StoragePersistence;
use Pam\Native\Disposable;
use Pam\Native\Scheduling\CancellationToken;
use Pam\Native\Scheduling\ScheduledTask;
use Pam\Native\Scheduling\Scheduler;
use Pam\Native\Scheduling\TaskCancelledException;
use Pam\Native\Scheduling\TaskPriority;
use Pam\Native\Signals\EffectScope;
use Pam\Native\Signals\Signal;
use Pam\Native\Signals\Watcher;
use ReflectionFunction;
use Throwable;

use function array_filter;
use function array_is_list;
use function array_key_exists;
use function array_map;
use function array_slice;
use function array_values;
use function count;
use function hash;
use function is_array;
use function is_int;
use function is_object;
use function is_string;
use function method_exists;
use function preg_match;
use function preg_quote;
use function property_exists;
use function serialize;
use function str_starts_with;

/**
 * `useResource()`: data loaded through the scheduler, with pagination in both
 * directions, a memory or persistent cache, reactive dependencies, focus
 * refresh and optimistic updates.
 *
 * ```php
 * $feed = useResource(fn (?string $cursor) => Api::client()->get('/feed', ['cursor' => $cursor])->json(),
 *     paginate: true, refreshOnFocus: true,
 *     cache: ['key' => 'feed', 'scope' => $auth->userId, 'persist' => true]);
 * ```
 *
 * - Requests run on the cooperative Scheduler (never inside the event that
 *   asked for them) and are cancelled when the owning scope is disposed.
 * - The last request wins: reload() cancels a more() in flight, a reload with
 *   new parameters (around:, changed `key:` dependencies) restarts instead of
 *   joining, and a result whose request was superseded is dropped.
 * - The loader receives `(?string $cursor, CancellationToken $token, ResourcePage $page)`
 *   when paginated, `(CancellationToken $token, ResourcePage $page)` otherwise
 *   (declare only what you use). A thenable result (an HTTP request) settles
 *   later and is cancelled with its request.
 *
 * @property-read mixed $data the result (the accumulated list when paginated)
 * @property-read bool $loading the first load or a reload without data is running
 * @property-read bool $refreshing a reload is running while data is shown
 * @property-read bool $loadingMore more() (older) is running
 * @property-read bool $loadingNewer more('newer') is running
 * @property-read string|null $error message of the last failure
 * @property-read bool $hasMore an older page is available
 * @property-read bool $hasNewer a newer page is available (opened around an item, or a previous cursor)
 * @property-read bool $stale the data came from the cache and is being refreshed
 * @property-read AsyncStatus $status
 * @property-read AsyncValue $value the state for <Suspense>
 */
final class Resource extends ReactiveObject implements Disposable
{
    private const int CACHE_ITEMS = 64;
    private const int CACHE_BYTES = 4_194_304;
    private const int PERSIST_ITEMS = 32;
    private const int PERSIST_BYTES = 1_048_576;
    private const int PERSIST_LIST_LIMIT = 100;

    private const string FIRST = 'first';
    private const string OLDER = 'older';
    private const string NEWER = 'newer';

    private static ?BoundedStore $store = null;
    /** @var array<string, BoundedStore> persistent stores by scope ('' without one) */
    private static array $persistent = [];
    /** @var array<string, list<Closure(): void>|true> waiters of a hydrating store, or true once hydrated */
    private static array $hydration = [];
    private static ?CachePersistence $persistence = null;
    private static ?int $backgroundListener = null;

    /** @var Signal<array<string, mixed>> */
    private Signal $state;
    /** @var array<string, array{ticket: int, task: ?ScheduledTask, thenable: ?object, params: array<string, mixed>}> */
    private array $requests = [];
    private int $tickets = 0;
    private bool $started = false;
    private bool $disposed = false;
    private bool $settledOnce = false;
    private int $focusCount = 0;
    /** @var list<mixed> optimistic items marked failed */
    private array $failed = [];
    private ?Watcher $dependencies = null;
    private mixed $deps = null;
    private ?string $cacheBase = null;
    private ?string $cacheScope = null;
    private bool $persist = false;
    private int $persistLimit = self::PERSIST_LIST_LIMIT;
    /** @var list<string>|null identity fields, null with a closure */
    private ?array $identityFields = null;
    private ?Closure $identityMatch = null;
    private int $loaderArity;
    /** @var (Closure(string, mixed...): mixed)|null app cache adapter: ($id) reads, ($id, $entry) writes */
    private ?Closure $cacheAdapter = null;
    /** @var array<int, Closure(mixed, ?string): void> */
    private array $settledListeners = [];
    /** @var array<int, Closure(mixed, string): void> */
    private array $pageListeners = [];
    private int $nextListener = 1;

    /**
     * @param Closure(mixed...): mixed $loader
     * @param string|array{key: string, scope?: string|int|null, persist?: bool, limit?: int, store?: callable}|null $cache
     * @param string|(Closure(): mixed) $key a Closure: reactive dependencies (a change cancels, swaps the cache and reloads); a string: the identity field (legacy, use $identity)
     * @param (Closure(mixed): ?string)|null $nextCursor reads the older-page cursor from a page
     * @param (Closure(mixed): list<mixed>)|null $items reads the items from a page
     * @param string|list<string>|(Closure(mixed, mixed): bool)|null $identity how items are matched (optimistic helpers, page merges)
     * @param (Closure(mixed): ?string)|null $previousCursor reads the newer-page cursor from a page
     * @param mixed $initial data (or first page when paginated) shown at once, e.g. from an app cache
     * @param bool $manual never loads by itself (construction, reads, key changes): only load()/reload()
     */
    public function __construct(
        private readonly Closure $loader,
        private readonly bool $paginate = false,
        bool $refreshOnFocus = false,
        private readonly bool $lazy = false,
        string|array|null $cache = null,
        string|Closure $key = 'id',
        private readonly ?Closure $nextCursor = null,
        private readonly ?Closure $items = null,
        string|array|Closure|null $identity = null,
        private readonly ?Closure $previousCursor = null,
        private mixed $around = null,
        mixed $initial = null,
        private readonly bool $manual = false,
    ) {
        $this->loaderArity = self::arity($loader);
        $identity ??= is_string($key) ? $key : 'id';
        if ($identity instanceof Closure) {
            $this->identityMatch = $identity;
        } else {
            $fields = is_string($identity) ? [$identity] : array_values($identity);
            if ($fields === []) throw new InvalidArgumentException('useResource() identity needs at least one field.');
            $this->identityFields = $fields;
        }
        $this->configureCache($cache);
        if ($key instanceof Closure) {
            $this->deps = $key();
            $this->dependencies = new Watcher($key, fn (mixed $next) => $this->dependenciesChanged($next));
        }

        $this->state = new Signal(self::emptyState($paginate));
        $this->showCached();
        if ($initial !== null && !$this->hasData()) {
            $this->seed($initial);
        }

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
        if (!$lazy && !$manual) {
            $this->start();
        }
    }

    /** Starts a lazy or manual resource; a no-op once started (reload() fetches again). */
    public function load(): void
    {
        $this->start();
    }

    /**
     * Calls $listener($data, $error) each time the first page (or a reload)
     * settles: $error is null on success, the message on failure.
     *
     * @param Closure(mixed, ?string): void $listener
     * @return Closure(): void unsubscribes
     */
    public function onSettled(Closure $listener): Closure
    {
        $id = $this->nextListener++;
        $this->settledListeners[$id] = $listener;

        return function () use ($id): void {
            unset($this->settledListeners[$id]);
        };
    }

    /**
     * Calls $listener($page, $direction) with each page the loader returned
     * ('initial', 'older' or 'newer'), before it is merged into data.
     *
     * @param Closure(mixed, string): void $listener
     * @return Closure(): void unsubscribes
     */
    public function onPage(Closure $listener): Closure
    {
        $id = $this->nextListener++;
        $this->pageListeners[$id] = $listener;

        return function () use ($id): void {
            unset($this->pageListeners[$id]);
        };
    }

    protected function properties(): array
    {
        return ['data', 'loading', 'refreshing', 'loadingMore', 'loadingNewer', 'error', 'hasMore', 'hasNewer', 'stale', 'status', 'value'];
    }

    protected function read(string $name): mixed
    {
        if ($this->lazy && !$this->manual && !$this->started) {
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

    /**
     * Loads the first page again. A reload already in flight with the same
     * parameters is joined; one with another `around` restarts. Any more()
     * in flight is cancelled (its page would belong to the old list).
     */
    public function reload(mixed $around = null): void
    {
        if ($this->disposed) {
            return;
        }
        $this->started = true;
        if ($around !== null) $this->around = $around;
        $params = ['around' => $this->around, 'deps' => $this->depsHash()];
        $current = $this->requests[self::FIRST] ?? null;
        if ($current !== null && $current['params'] === $params) {
            return;
        }
        $this->cancel(self::FIRST);
        $this->cancel(self::OLDER);
        $this->cancel(self::NEWER);
        $state = $this->state->peek();
        $hasData = $state['data'] !== null && $state['data'] !== [];
        $this->patch([
            'loading' => !$hasData,
            'refreshing' => $hasData,
            'loadingMore' => false,
            'loadingNewer' => false,
            'error' => null,
        ]);
        $this->request(self::FIRST, null, $params, TaskPriority::UserBlocking);
    }

    /**
     * Loads the next page (paginate): 'older' (default) appends with the
     * next cursor, 'newer' prepends with the previous cursor. A no-op while
     * the first page loads, at the end, or while the same direction runs.
     */
    public function more(string $direction = 'older'): void
    {
        if ($direction !== self::OLDER && $direction !== self::NEWER) {
            throw new InvalidArgumentException("more() direction must be 'older' or 'newer', '{$direction}' given.");
        }
        if ($this->disposed || !$this->paginate || isset($this->requests[self::FIRST]) || isset($this->requests[$direction])) {
            return;
        }
        $state = $this->state->peek();
        $cursor = $direction === self::OLDER ? $state['next'] : $state['previous'];
        if ($cursor === null) {
            return;
        }
        $this->patch([$direction === self::OLDER ? 'loadingMore' : 'loadingNewer' => true, 'error' => null]);
        $this->request($direction, $cursor, ['cursor' => $cursor], TaskPriority::Background);
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

    /**
     * Swaps the optimistic $temporary (item or key) for the server's $real
     * item. When the server item is already listed (a page arrived first),
     * one copy is kept.
     */
    public function replace(mixed $temporary, mixed $real): void
    {
        $this->unfail($temporary);
        $this->mutate(function (array $items) use ($temporary, $real): array {
            $out = [];
            $placed = false;
            foreach ($items as $item) {
                if ($this->matches($item, $temporary) || $this->matches($item, $real)) {
                    if (!$placed) $out[] = $real;
                    $placed = true;
                    continue;
                }
                $out[] = $item;
            }

            return $out;
        });
    }

    /** Flags an optimistic item whose request failed (`item._failed` in templates; isFailed()). */
    public function markFailed(mixed $temporary): void
    {
        $this->failed[] = $temporary;
        $this->mutate(fn (array $items): array => array_map(
            fn (mixed $item): mixed => is_array($item) && $this->matches($item, $temporary) ? [...$item, '_failed' => true] : $item,
            $items,
        ));
    }

    public function isFailed(mixed $item): bool
    {
        if (is_array($item) && ($item['_failed'] ?? false) === true) return true;
        foreach ($this->failed as $failed) {
            if ($this->matches($item, $failed) || $this->matches($failed, $item)) return true;
        }

        return false;
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
        foreach ([self::FIRST, self::OLDER, self::NEWER] as $kind) {
            $this->cancel($kind);
        }
        $this->dependencies?->dispose();
        $this->dependencies = null;
        $this->settledListeners = [];
        $this->pageListeners = [];
    }

    public function disposed(): bool
    {
        return $this->disposed;
    }

    /** @internal Shared in-memory result cache (also used by tests). */
    public static function store(): BoundedStore
    {
        return self::$store ??= new BoundedStore('pam-resources', self::CACHE_ITEMS, maxBytes: self::CACHE_BYTES);
    }

    /**
     * Forgets cached results: one key (every scope and dependency variant,
     * or only $scope's), one scope (on logout: `Resource::forgetCache(scope: $userId)`,
     * memory and disk), or everything.
     */
    public static function forgetCache(?string $key = null, string|int|null $scope = null): void
    {
        $scopeName = $scope === null ? null : self::scopeName((string) $scope);
        $memory = self::store();
        if ($key === null && $scopeName === null) {
            $memory->clear();
        } else {
            foreach ($memory->keys() as $cached) {
                if (self::cacheIdMatches((string) $cached, $key, $scopeName)) $memory->forget((string) $cached);
            }
        }
        if ($scopeName !== null && !isset(self::$persistent[$scopeName])) {
            self::persistentStore($scopeName);
        }
        foreach (self::$persistent as $storeScope => $store) {
            if ($scopeName !== null && (string) $storeScope !== $scopeName) continue;
            if ($key === null) {
                $store->clear();
            } else {
                foreach ($store->keys() as $cached) {
                    if (self::cacheIdMatches((string) $cached, $key, null)) $store->forget((string) $cached);
                }
            }
            $store->persist(force: true);
        }
    }

    /** Writes the persistent caches that changed (done automatically when the app goes to the background). */
    public static function persistCache(): void
    {
        foreach (self::$persistent as $store) {
            $store->persist();
        }
    }

    /**
     * @internal Where persistent caches are saved (default: the native
     * Storage). Tests pass a MemoryPersistence; every call forgets the
     * stores, as a cold start does.
     */
    public static function usePersistence(?CachePersistence $persistence): void
    {
        self::$persistence = $persistence;
        self::$persistent = [];
        self::$hydration = [];
    }

    private function start(): void
    {
        if ($this->started) {
            return;
        }
        $this->reload();
    }

    // ------------------------------------------------------------ requests

    /** @param array<string, mixed> $params */
    private function request(string $kind, ?string $cursor, array $params, TaskPriority $priority): void
    {
        $ticket = ++$this->tickets;
        $this->requests[$kind] = ['ticket' => $ticket, 'task' => null, 'thenable' => null, 'params' => $params];
        $page = new ResourcePage(
            $kind === self::FIRST ? ResourcePage::INITIAL : $kind,
            $cursor,
            $kind === self::FIRST ? $this->around : null,
            $this->deps,
        );
        $task = Scheduler::schedule(function (CancellationToken $token) use ($kind, $ticket, $cursor, $page): void {
            if (!$this->current($kind, $ticket)) return;
            try {
                $arguments = $this->paginate ? [$cursor, $token, $page] : [$token, $page];
                $result = ($this->loader)(...array_slice($arguments, 0, $this->loaderArity));
                $token->throwIfCancelled();
            } catch (TaskCancelledException) {
                return;
            } catch (Throwable $error) {
                $this->failed($kind, $ticket, $error->getMessage());

                return;
            }
            if (is_object($result) && method_exists($result, 'then')) {
                if (!$this->current($kind, $ticket)) return;
                $this->requests[$kind]['thenable'] = $result;
                $result->then(
                    fn (mixed $value) => $this->settled($kind, $ticket, $value),
                    function (mixed $error) use ($kind, $ticket): void {
                        $message = $error instanceof Throwable ? $error->getMessage() : (is_string($error) ? $error : 'Request failed.');
                        $this->failed($kind, $ticket, $message);
                    },
                );

                return;
            }
            $this->settled($kind, $ticket, $result);
        }, $priority);
        if ($this->current($kind, $ticket)) $this->requests[$kind]['task'] = $task;
    }

    private function current(string $kind, int $ticket): bool
    {
        return !$this->disposed && ($this->requests[$kind]['ticket'] ?? null) === $ticket;
    }

    private function cancel(string $kind): void
    {
        $request = $this->requests[$kind] ?? null;
        if ($request === null) return;
        unset($this->requests[$kind]);
        $request['task']?->cancel();
        $thenable = $request['thenable'];
        if ($thenable !== null && method_exists($thenable, 'cancel')) $thenable->cancel();
    }

    private function failed(string $kind, int $ticket, string $message): void
    {
        if (!$this->current($kind, $ticket)) return;
        unset($this->requests[$kind]);
        $message = $message === '' ? 'Request failed.' : $message;
        $this->patch([...self::idle($kind), 'error' => $message, 'status' => AsyncStatus::Error]);
        if ($kind === self::FIRST) $this->emitSettled($message);
    }

    private function emitSettled(?string $error): void
    {
        $data = $this->state->peek()['data'];
        foreach ($this->settledListeners as $listener) $listener($data, $error);
    }

    private function hasData(): bool
    {
        $data = $this->state->peek()['data'];

        return $data !== null && $data !== [];
    }

    /** Shows $initial as stale data until the first load settles. */
    private function seed(mixed $initial): void
    {
        if (!$this->paginate) {
            $this->patch(['data' => $initial, 'stale' => true, 'status' => AsyncStatus::Stale]);

            return;
        }
        $data = $this->merged([], $this->itemsOf($initial));
        $next = $this->cursorOf($initial, false);
        $previous = $this->cursorOf($initial, true);
        $this->patch(['data' => $data, 'next' => $next, 'previous' => $previous, 'hasMore' => $next !== null,
            'hasNewer' => $previous !== null, 'stale' => true, 'status' => $data === [] ? AsyncStatus::Empty : AsyncStatus::Stale]);
    }

    private function settled(string $kind, int $ticket, mixed $value): void
    {
        if (!$this->current($kind, $ticket)) return;
        unset($this->requests[$kind]);
        $this->settledOnce = true;
        foreach ($this->pageListeners as $listener) $listener($value, $kind === self::FIRST ? ResourcePage::INITIAL : $kind);
        if (!$this->current($kind, $ticket) && isset($this->requests[$kind])) return;
        $flags = self::idle($kind);
        if (!$this->paginate) {
            $empty = $value === null || $value === [];
            $this->patch([...$flags, 'data' => $empty ? null : $value, 'error' => null, 'stale' => false,
                'status' => $empty ? AsyncStatus::Empty : AsyncStatus::Content]);
            $this->remember();
            $this->emitSettled(null);

            return;
        }
        $items = $this->itemsOf($value);
        $current = $this->state->peek();
        $data = match ($kind) {
            self::FIRST => $this->merged([], $items),
            self::OLDER => $this->merged($current['data'], $items),
            default => $this->merged($current['data'], $items, front: true),
        };
        $next = $kind === self::NEWER ? $current['next'] : $this->cursorOf($value, false);
        $previous = $kind === self::OLDER ? $current['previous'] : $this->cursorOf($value, true);
        $this->patch([
            ...$flags,
            'data' => $data,
            'next' => $next,
            'previous' => $previous,
            'hasMore' => $next !== null,
            'hasNewer' => $previous !== null,
            'error' => null,
            'stale' => false,
            'status' => $data === [] ? AsyncStatus::Empty : AsyncStatus::Content,
        ]);
        $this->remember();
        if ($kind === self::FIRST) $this->emitSettled(null);
    }

    /** @return array<string, false> */
    private static function idle(string $kind): array
    {
        return match ($kind) {
            self::OLDER => ['loadingMore' => false],
            self::NEWER => ['loadingNewer' => false],
            default => ['loading' => false, 'refreshing' => false],
        };
    }

    /** @return array<string, mixed> */
    private static function emptyState(bool $paginate): array
    {
        return [
            'data' => $paginate ? [] : null,
            'loading' => false,
            'refreshing' => false,
            'loadingMore' => false,
            'loadingNewer' => false,
            'error' => null,
            'next' => null,
            'previous' => null,
            'hasMore' => false,
            'hasNewer' => false,
            'stale' => false,
            'status' => AsyncStatus::Loading,
        ];
    }

    /** Arguments the loader declares (extra ones are not passed; a variadic loader takes all). */
    private static function arity(Closure $loader): int
    {
        $function = new ReflectionFunction($loader);

        return $function->isVariadic() ? 3 : $function->getNumberOfParameters();
    }

    // ------------------------------------------------------------ dependencies

    private function dependenciesChanged(mixed $deps): void
    {
        if ($this->disposed) return;
        $this->deps = $deps;
        foreach ([self::FIRST, self::OLDER, self::NEWER] as $kind) {
            $this->cancel($kind);
        }
        $this->failed = [];
        $this->settledOnce = false;
        $this->state->set(self::emptyState($this->paginate));
        $this->showCached();
        if ($this->started) {
            $this->started = false;
            if (!$this->manual) $this->start();
        }
    }

    private function depsHash(): ?string
    {
        return $this->deps === null ? null : hash('xxh3', serialize($this->deps));
    }

    // ------------------------------------------------------------ cache

    /** @param string|array<string, mixed>|null $cache */
    private function configureCache(string|array|null $cache): void
    {
        if ($cache === null) return;
        if (is_string($cache)) {
            $this->cacheBase = $cache;

            return;
        }
        $key = $cache['key'] ?? null;
        if (!is_string($key) || $key === '') {
            throw new InvalidArgumentException("useResource() cache needs a 'key' string.");
        }
        $this->cacheBase = $key;
        $adapter = $cache['store'] ?? null;
        if ($adapter !== null) {
            if (!is_callable($adapter)) throw new InvalidArgumentException("useResource() cache 'store' must be callable: (\$id) reads, (\$id, \$entry) writes.");
            $this->cacheAdapter = Closure::fromCallable($adapter);
        }
        $scope = $cache['scope'] ?? null;
        $this->cacheScope = $scope === null ? null : self::scopeName((string) $scope);
        $this->persist = (bool) ($cache['persist'] ?? false);
        $limit = $cache['limit'] ?? self::PERSIST_LIST_LIMIT;
        if (!is_int($limit) || $limit < 1) throw new InvalidArgumentException('useResource() cache limit must be a positive integer.');
        $this->persistLimit = $limit;
    }

    /** Cache entry id: key, scope and dependencies, so accounts and queries never share results. */
    private function cacheId(): ?string
    {
        if ($this->cacheBase === null) return null;
        $id = $this->cacheBase;
        if ($this->cacheScope !== null) $id .= '@'.$this->cacheScope;
        $deps = $this->depsHash();
        if ($deps !== null) $id .= '#'.$deps;

        return $id;
    }

    private static function cacheIdMatches(string $id, ?string $key, ?string $scope): bool
    {
        if ($key !== null && $id !== $key && !str_starts_with($id, $key.'@') && !str_starts_with($id, $key.'#')) return false;
        if ($scope !== null && preg_match('/@'.preg_quote($scope, '/').'(#|$)/', $id) !== 1) return false;

        return true;
    }

    private function cacheStore(): BoundedStore
    {
        return $this->persist ? self::persistentStore($this->cacheScope ?? '') : self::store();
    }

    /** Shows the cached entry as stale data, now or when the persistent store finishes loading. */
    private function showCached(): void
    {
        if ($this->cacheId() === null) return;
        if ($this->persist && $this->cacheAdapter === null) {
            $scope = $this->cacheScope ?? '';
            self::persistentStore($scope);
            if ((self::$hydration[$scope] ?? null) !== true) {
                $deps = $this->depsHash();
                self::whenHydrated($scope, function () use ($deps): void {
                    // Too late when the network answered first or the dependencies moved on.
                    if ($this->disposed || $this->settledOnce || $deps !== $this->depsHash()) return;
                    $this->applyCached();
                });

                return;
            }
        }
        $this->applyCached();
    }

    private function applyCached(): void
    {
        $id = $this->cacheId();
        $cached = $id === null ? null : ($this->cacheAdapter !== null ? ($this->cacheAdapter)($id) : $this->cacheStore()->get($id));
        if (!is_array($cached) || !array_key_exists('data', $cached)) return;
        $next = is_string($cached['next'] ?? null) ? $cached['next'] : null;
        $previous = is_string($cached['previous'] ?? null) ? $cached['previous'] : null;
        $state = $this->state->peek();
        $this->patch([
            'data' => $cached['data'],
            'next' => $next,
            'previous' => $previous,
            'hasMore' => $next !== null,
            'hasNewer' => $previous !== null,
            'stale' => true,
            'status' => AsyncStatus::Stale,
            // A load already running becomes a refresh behind the cached data.
            'loading' => false,
            'refreshing' => $state['refreshing'] || $state['loading'],
        ]);
    }

    private function remember(): void
    {
        $id = $this->cacheId();
        if ($id === null) {
            return;
        }
        $state = $this->state->peek();
        $data = $state['data'];
        $next = $state['next'];
        if ($this->persist && $this->paginate && is_array($data) && count($data) > $this->persistLimit) {
            // Bounded on disk: the first items, without a cursor that would skip the rest.
            $data = array_slice(array_values($data), 0, $this->persistLimit);
            $next = null;
        }
        $entry = ['data' => $data, 'next' => $next, 'previous' => $state['previous']];
        if ($this->cacheAdapter !== null) {
            ($this->cacheAdapter)($id, $entry);

            return;
        }
        $this->cacheStore()->put($id, $entry);
    }

    private static function scopeName(string $scope): string
    {
        return preg_match('/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,63}$/D', $scope) === 1 ? $scope : 'h'.hash('xxh3', $scope);
    }

    private static function persistentStore(string $scope): BoundedStore
    {
        if (isset(self::$persistent[$scope])) return self::$persistent[$scope];
        self::$persistent[$scope] = $store = new BoundedStore(
            'pam-resources',
            self::PERSIST_ITEMS,
            maxBytes: self::PERSIST_BYTES,
            scope: $scope === '' ? null : $scope,
            persistence: self::$persistence ??= new StoragePersistence(),
        );
        self::$hydration[$scope] = [];
        $store->hydrate(static function () use ($scope): void {
            $waiters = self::$hydration[$scope] ?? [];
            self::$hydration[$scope] = true;
            if (is_array($waiters)) {
                foreach ($waiters as $waiter) $waiter();
            }
        });
        if (self::$backgroundListener === null) {
            self::$backgroundListener = App::onStateChange(static function (AppState $state): void {
                if ($state === AppState::Background) self::persistCache();
            });
        }

        return $store;
    }

    /** @param Closure(): void $callback */
    private static function whenHydrated(string $scope, Closure $callback): void
    {
        $waiters = self::$hydration[$scope] ?? [];
        if ($waiters === true) {
            $callback();

            return;
        }
        $waiters[] = $callback;
        self::$hydration[$scope] = $waiters;
    }

    // ------------------------------------------------------------ pages and identity

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

    private function cursorOf(mixed $page, bool $previous): ?string
    {
        $reader = $previous ? $this->previousCursor : $this->nextCursor;
        if ($reader !== null) {
            return $reader($page);
        }
        if (!is_array($page) || array_is_list($page)) {
            return null;
        }
        $meta = is_array($page['meta'] ?? null) ? $page['meta'] : [];
        $cursor = $previous
            ? $page['previous'] ?? $page['prev'] ?? $page['prev_cursor'] ?? $page['previous_cursor'] ?? $page['prevCursor'] ?? $page['previousCursor']
                ?? $meta['prev_cursor'] ?? $meta['previous_cursor'] ?? $meta['prevCursor'] ?? $meta['previousCursor'] ?? null
            : $page['next'] ?? $page['next_cursor'] ?? $page['nextCursor'] ?? $meta['next_cursor'] ?? $meta['nextCursor'] ?? null;

        return is_string($cursor) && $cursor !== '' ? $cursor : (is_int($cursor) ? (string) $cursor : null);
    }

    /**
     * Adds a page to the list. An item already listed (same identity: an
     * optimistic copy, or a page that shifted) is replaced in place by the
     * server's version instead of being listed twice.
     *
     * @param list<mixed> $items
     * @return list<mixed>
     */
    private function merged(mixed $current, array $items, bool $front = false): array
    {
        $current = is_array($current) ? array_values($current) : [];
        $added = [];
        foreach ($items as $item) {
            $index = $this->indexOf($current, $item);
            if ($index !== null) {
                $current[$index] = $item;
                continue;
            }
            $index = $this->indexOf($added, $item);
            if ($index !== null) {
                $added[$index] = $item;
                continue;
            }
            $added[] = $item;
        }

        return $front ? [...$added, ...$current] : [...$current, ...$added];
    }

    /** @param list<mixed> $list */
    private function indexOf(array $list, mixed $item): ?int
    {
        foreach ($list as $index => $candidate) {
            if ($this->matches($candidate, $item)) return $index;
        }

        return null;
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

    /** @param array<string, mixed> $changes */
    private function patch(array $changes): void
    {
        $this->state->set([...$this->state->peek(), ...$changes]);
    }

    /** True when $item is $target, or both have the same identity ($target may be a key). */
    private function matches(mixed $item, mixed $target): bool
    {
        if ($item === $target) {
            return true;
        }
        if ($this->identityMatch !== null) {
            return (bool) ($this->identityMatch)($item, $target);
        }
        $scalar = is_int($target) || is_string($target);
        foreach ($this->identityFields ?? [] as $field) {
            $left = self::field($item, $field);
            if ($left === null) continue;
            $right = $scalar ? (string) $target : self::field($target, $field);
            if ($right !== null && $left === $right) return true;
        }

        return false;
    }

    private static function field(mixed $item, string $field): ?string
    {
        $value = match (true) {
            is_array($item) => $item[$field] ?? null,
            is_object($item) && property_exists($item, $field) => $item->{$field},
            default => null,
        };

        return is_int($value) || is_string($value) ? (string) $value : null;
    }

    private function unfail(mixed $target): void
    {
        $this->failed = array_values(array_filter(
            $this->failed,
            fn (mixed $failed): bool => !$this->matches($failed, $target) && !$this->matches($target, $failed),
        ));
    }
}
