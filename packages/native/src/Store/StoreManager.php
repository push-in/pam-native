<?php

declare(strict_types=1);

namespace Pam\Native\Store;

use Closure;
use LogicException;
use Pam\Native\Internal\Runtime;
use Throwable;
use Pam\Native\Diagnostics\Profiler;

final class StoreManager
{
    private const HISTORY_LIMIT = 200;

    private static ?self $instance = null;

    /** @var array<class-string<Store>, Store> */
    private array $stores = [];

    /** @var list<StoreMiddleware> */
    private array $middleware = [];

    /** @var array<string, array<int, Closure>> */
    private array $subscribers = [];

    /** @var list<StoreChange> */
    private array $history = [];

    /** @var array<string, list<array<string, mixed>>> */
    private array $undo = [];

    /** @var array<string, list<array<string, mixed>>> */
    private array $redo = [];

    /** @var array<string, int> */
    private array $running = [];

    private int $nextSubscription = 1;
    private int $nextChange = 1;
    private int $depth = 0;
    private ?Store $activeStore = null;
    private ?string $activeName = null;
    /** @var array<string, mixed>|null */
    private ?array $before = null;
    private StorePersistence $persistence;

    private function __construct()
    {
        $this->persistence = new StatePersistence();
    }

    public static function instance(): self
    {
        return self::$instance ??= new self();
    }

    public static function resetInstance(): void
    {
        self::$instance = null;
    }

    /** @template T of Store @param class-string<T> $class @return T */
    public function get(string $class): Store
    {
        if (!is_a($class, Store::class, true)) {
            throw new LogicException("{$class} is not a Pam Store.");
        }

        return $this->stores[$class] ?? new $class();
    }

    public function attach(Store $store): void
    {
        $class = $store::class;
        if (isset($this->stores[$class])) {
            throw new LogicException("Store {$class} already exists; resolve it through Stores::get().");
        }
        $this->stores[$class] = $store;
        $keys = $store->__pamPersistedKeys();
        if ($keys === []) {
            return;
        }
        $payload = $this->persistence->load($store->key());
        if ($payload === null) {
            return;
        }
        $version = $payload['version'];
        $persisted = $payload['state'];
        $target = $store->__pamVersion();
        if ($version > $target) {
            throw new LogicException("Persisted {$class} version {$version} is newer than {$target}.");
        }
        $migrations = [];
        foreach ($store->__pamMigrations() as $migration) {
            $migrations[$migration->fromVersion()] = $migration;
        }
        while ($version < $target) {
            $migration = $migrations[$version] ?? null;
            if ($migration === null) {
                throw new LogicException("Store {$class} is missing migration from version {$version}.");
            }
            $persisted = $migration->migrate($persisted);
            $version++;
        }
        $state = $store->snapshot();
        foreach ($keys as $key) {
            if (array_key_exists($key, $persisted)) {
                $state[$key] = $persisted[$key];
            }
        }
        $store->__pamReplace($state);
        $this->record($store, 'hydrate', StoreChangeKind::Hydration, [], $state);
    }

    public function mutate(Store $store, string $name, mixed $value): void
    {
        if ($this->depth === 0) {
            $this->transaction($store, "set:{$name}", static function () use ($store, $name, $value): void {
                $store->__pamSet($name, $value);
            });

            return;
        }
        if ($this->activeStore !== $store) {
            throw new LogicException('A transaction cannot mutate a different store.');
        }
        $store->__pamSet($name, $value);
    }

    /** @param array<string, mixed> $arguments */
    public function dispatch(
        Store $store,
        string $action,
        array $arguments,
        ActionPolicy $policy,
        int $debounceMs,
        Closure $invoke,
    ): mixed {
        $key = $store->key().':'.$action;
        if (($policy === ActionPolicy::Leading || $policy === ActionPolicy::Debounced) && isset($this->running[$key])) {
            return null;
        }
        if ($policy === ActionPolicy::Debounced && ($debounceMs < 1 || $debounceMs > 60_000)) {
            throw new LogicException('Debounced actions require debounceMs between 1 and 60000.');
        }
        $token = ($this->running[$key] ?? 0) + 1;
        $this->running[$key] = $token;

        $next = fn (): mixed => Profiler::measure(
            'store.action',
            fn (): mixed => $this->transaction($store, $action, $invoke),
            ['store' => $store->key(), 'action' => $action],
        );
        foreach (array_reverse($this->middleware) as $middleware) {
            $downstream = $next;
            $next = static fn (): mixed => $middleware->handle($store, $action, $arguments, $downstream);
        }
        try {
            return $next();
        } finally {
            if (($this->running[$key] ?? null) === $token) {
                unset($this->running[$key]);
            }
        }
    }

    public function transaction(Store $store, string $name, Closure $callback): mixed
    {
        $outer = $this->depth === 0;
        if ($outer) {
            $this->activeStore = $store;
            $this->activeName = $name;
            $this->before = $store->snapshot();
        } elseif ($this->activeStore !== $store) {
            throw new LogicException('Nested transactions must target the same store.');
        }
        $this->depth++;
        try {
            return $callback();
        } catch (Throwable $error) {
            if ($outer && $this->before !== null) {
                $store->__pamReplace($this->before);
            }
            throw $error;
        } finally {
            $this->depth--;
            if ($outer) {
                $before = $this->before ?? [];
                $after = $store->snapshot();
                $action = $this->activeName ?? $name;
                $this->activeStore = null;
                $this->activeName = null;
                $this->before = null;
                if ($before !== $after) {
                    $this->undo[$store->key()][] = $before;
                    $this->trimSnapshots($this->undo[$store->key()]);
                    $this->redo[$store->key()] = [];
                    $this->record($store, $action, StoreChangeKind::Action, $before, $after);
                    $this->persist($store);
                    Runtime::requestRender();
                }
            }
        }
    }

    public function optimistic(Store $store, string $name, Closure $apply, Closure $task, ?Closure $rollback = null): mixed
    {
        $before = $store->snapshot();
        $this->transaction($store, $name, $apply);
        try {
            return $task();
        } catch (Throwable $error) {
            $current = $store->snapshot();
            if ($rollback !== null) {
                $this->transaction($store, "{$name}:rollback", $rollback);
            } else {
                $store->__pamReplace($before);
                $this->record($store, "{$name}:rollback", StoreChangeKind::Rollback, $current, $before);
                $this->persist($store);
                Runtime::requestRender();
            }
            throw $error;
        }
    }

    public function undo(Store $store): bool
    {
        $key = $store->key();
        $state = array_pop($this->undo[$key]);
        if (!is_array($state)) {
            return false;
        }
        $before = $store->snapshot();
        $this->redo[$key][] = $before;
        $store->__pamReplace($state);
        $this->record($store, 'undo', StoreChangeKind::Undo, $before, $state);
        $this->persist($store);
        Runtime::requestRender();

        return true;
    }

    public function redo(Store $store): bool
    {
        $key = $store->key();
        $state = array_pop($this->redo[$key]);
        if (!is_array($state)) {
            return false;
        }
        $before = $store->snapshot();
        $this->undo[$key][] = $before;
        $store->__pamReplace($state);
        $this->record($store, 'redo', StoreChangeKind::Redo, $before, $state);
        $this->persist($store);
        Runtime::requestRender();

        return true;
    }

    public function reset(Store $store): void
    {
        $before = $store->snapshot();
        $after = $store->__pamInitialState();
        $store->__pamReplace($after);
        $this->undo[$store->key()] = [];
        $this->redo[$store->key()] = [];
        $this->persistence->forget($store->key());
        $this->record($store, 'reset', StoreChangeKind::Reset, $before, $after);
        Runtime::requestRender();
    }

    public function subscribe(Store $store, Closure $listener): int
    {
        $id = $this->nextSubscription++;
        $this->subscribers[$store->key()][$id] = $listener;

        return $id;
    }

    public function unsubscribe(Store $store, int $subscription): void
    {
        unset($this->subscribers[$store->key()][$subscription]);
    }

    public function middleware(StoreMiddleware $middleware): void
    {
        $this->middleware[] = $middleware;
    }

    public function persistence(StorePersistence $persistence): void
    {
        if ($this->stores !== []) {
            throw new LogicException('Configure store persistence before resolving stores.');
        }
        $this->persistence = $persistence;
    }

    /** @return list<StoreChange> */
    public function history(): array
    {
        return $this->history;
    }

    public function timeTravel(int $changeId): bool
    {
        foreach ($this->history as $change) {
            if ($change->id !== $changeId) {
                continue;
            }
            foreach ($this->stores as $store) {
                if ($store->key() !== $change->store) {
                    continue;
                }
                $before = $store->snapshot();
                $after = $before;
                foreach ($change->diff as $key => $values) {
                    $after[$key] = $values['after'];
                }
                $store->__pamReplace($after);
                $this->record($store, "time-travel:{$changeId}", StoreChangeKind::TimeTravel, $before, $after);
                Runtime::requestRender();

                return true;
            }
        }

        return false;
    }

    private function persist(Store $store): void
    {
        $keys = $store->__pamPersistedKeys();
        if ($keys === []) {
            return;
        }
        $state = array_intersect_key($store->snapshot(), array_flip($keys));
        $this->persistence->save($store->key(), $store->__pamVersion(), $state);
    }

    /** @param array<string, mixed> $before @param array<string, mixed> $after */
    private function record(Store $store, string $name, StoreChangeKind $kind, array $before, array $after): void
    {
        $diff = [];
        foreach ($after as $key => $value) {
            $old = $before[$key] ?? null;
            if (!array_key_exists($key, $before) || $old !== $value) {
                $diff[$key] = ['before' => $old, 'after' => $value];
            }
        }
        $change = new StoreChange(
            id: $this->nextChange++,
            store: $store->key(),
            name: $name,
            kind: $kind,
            diff: $diff,
            timestamp: microtime(true),
        );
        $this->history[] = $change;
        if (count($this->history) > self::HISTORY_LIMIT) {
            array_shift($this->history);
        }
        foreach ($this->subscribers[$store->key()] ?? [] as $listener) {
            $listener($change, $store);
        }
    }

    /** @param list<array<string, mixed>> $snapshots */
    private function trimSnapshots(array &$snapshots): void
    {
        if (count($snapshots) > self::HISTORY_LIMIT) {
            array_shift($snapshots);
        }
    }
}
