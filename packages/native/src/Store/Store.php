<?php

declare(strict_types=1);

namespace Pam\Native\Store;

use BadMethodCallException;
use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\Store\Attributes\Computed;
use Pam\Native\Internal\DependencyTracker;
use ReflectionMethod;

abstract class Store
{
    /** @var array<string, mixed> */
    private array $values;

    /** @var array<string, array{revision: int, value: mixed}> */
    private array $computed = [];

    private int $revision = 0;

    final public function __construct()
    {
        $this->values = $this->initialState();
        StoreManager::instance()->attach($this);
    }

    /** @return array<string, mixed> */
    abstract protected function state(): array;

    /** Sequential schema version used by persistence migrations. */
    protected function version(): int
    {
        return 1;
    }

    /** @return list<string> State keys to persist. An empty list disables persistence. */
    protected function persist(): array
    {
        return [];
    }

    /** @return list<StoreMigration> */
    protected function migrations(): array
    {
        return [];
    }

    final public function __get(string $name): mixed
    {
        if (array_key_exists($name, $this->values)) {
            DependencyTracker::read($this, $name);
            return $this->values[$name];
        }

        if (method_exists($this, $name)) {
            $method = new ReflectionMethod($this, $name);
            if ($method->getAttributes(Computed::class) !== []) {
                $cached = $this->computed[$name] ?? null;
                if ($cached !== null && $cached['revision'] === $this->revision) {
                    return $cached['value'];
                }
                $value = $method->invoke($this);
                $this->computed[$name] = ['revision' => $this->revision, 'value' => $value];

                return $value;
            }
        }

        throw new LogicException("Unknown state or computed property {$name}.");
    }

    final public function __set(string $name, mixed $value): void
    {
        if (!array_key_exists($name, $this->values)) {
            throw new LogicException("Unknown state property {$name}.");
        }
        self::assertValue($value);
        if ($this->values[$name] === $value) {
            return;
        }
        StoreManager::instance()->mutate($this, $name, $value);
    }

    final public function __isset(string $name): bool
    {
        return isset($this->values[$name]) || method_exists($this, $name);
    }

    /**
     * Runs a named public store method as one action/commit.
     *
     * @param array<string, mixed> $arguments
     */
    final public function dispatch(
        string $action,
        array $arguments = [],
        ActionPolicy $policy = ActionPolicy::Every,
        int $debounceMs = 0,
    ): mixed {
        if (!method_exists($this, $action)) {
            throw new BadMethodCallException("Unknown store action {$action}.");
        }
        $method = new ReflectionMethod($this, $action);
        if (!$method->isPublic() || $method->isStatic() || $method->getDeclaringClass()->getName() === self::class) {
            throw new BadMethodCallException("{$action} is not a dispatchable store action.");
        }

        return StoreManager::instance()->dispatch(
            $this,
            $action,
            $arguments,
            $policy,
            $debounceMs,
            fn (): mixed => $method->invokeArgs($this, $arguments),
        );
    }

    final public function transaction(Closure $callback, string $name = 'transaction'): mixed
    {
        return StoreManager::instance()->transaction($this, $name, $callback);
    }

    final public function subscribe(Closure $listener): int
    {
        return StoreManager::instance()->subscribe($this, $listener);
    }

    final public function unsubscribe(int $subscription): void
    {
        StoreManager::instance()->unsubscribe($this, $subscription);
    }

    final public function undo(): bool
    {
        return StoreManager::instance()->undo($this);
    }

    final public function redo(): bool
    {
        return StoreManager::instance()->redo($this);
    }

    final public function reset(): void
    {
        StoreManager::instance()->reset($this);
    }

    final public function optimistic(
        string $name,
        Closure $apply,
        Closure $task,
        ?Closure $rollback = null,
    ): mixed {
        return StoreManager::instance()->optimistic($this, $name, $apply, $task, $rollback);
    }

    /** @return array<string, mixed> */
    final public function snapshot(): array
    {
        return $this->values;
    }

    final public function select(Closure $selector): mixed
    {
        return $selector($this);
    }

    final public function selector(Closure $selector): StoreSelector
    {
        return new StoreSelector($selector);
    }

    final public function key(): string
    {
        $key = str_replace('\\', '.', static::class);

        return preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,121}$/D', $key) === 1
            ? $key
            : 'anonymous.'.hash('xxh3', static::class);
    }

    /** @internal */
    final public function __pamReplace(array $state): void
    {
        if (array_keys($state) !== array_keys($this->values)) {
            throw new InvalidArgumentException('Replacement store state has a different shape.');
        }
        foreach ($state as $value) {
            self::assertValue($value);
        }
        $this->values = $state;
        $this->revision++;
        $this->computed = [];
    }

    /** @internal @return array<string, mixed> */
    final public function __pamInitialState(): array
    {
        return $this->initialState();
    }

    /** @internal */
    final public function __pamSet(string $name, mixed $value): void
    {
        $this->values[$name] = $value;
        DependencyTracker::invalidate($this, $name);
        $this->revision++;
        $this->computed = [];
    }

    /** @internal @return list<string> */
    final public function __pamPersistedKeys(): array
    {
        $keys = $this->persist();
        foreach ($keys as $key) {
            if (!is_string($key) || !array_key_exists($key, $this->values)) {
                throw new LogicException('Persisted store keys must name existing state.');
            }
        }

        return array_values(array_unique($keys));
    }

    /** @internal */
    final public function __pamVersion(): int
    {
        $version = $this->version();
        if ($version < 1) {
            throw new LogicException('Store versions must start at 1.');
        }

        return $version;
    }

    /** @internal @return list<StoreMigration> */
    final public function __pamMigrations(): array
    {
        return $this->migrations();
    }

    /** @return array<string, mixed> */
    private function initialState(): array
    {
        $state = $this->state();
        foreach ($state as $key => $value) {
            if (!is_string($key) || preg_match('/^[A-Za-z][A-Za-z0-9_]{0,127}$/D', $key) !== 1) {
                throw new InvalidArgumentException('Store state keys must be safe identifiers.');
            }
            self::assertValue($value);
        }

        return $state;
    }

    private static function assertValue(mixed $value): void
    {
        if (is_null($value) || is_scalar($value)) {
            return;
        }
        if (!is_array($value)) {
            throw new InvalidArgumentException('Store state supports only JSON scalar and array values.');
        }
        foreach ($value as $nested) {
            self::assertValue($nested);
        }
        json_encode($value, JSON_THROW_ON_ERROR);
    }
}
