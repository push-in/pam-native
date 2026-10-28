<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

use Closure;
use LogicException;
use Pam\Native\Disposable;
use Throwable;

use function array_key_last;
use function array_pop;
use function array_reverse;
use function count;

/**
 * Owner of everything a `setup()` (or a composable) creates: watchers,
 * composable subscriptions and lifecycle hooks. Disposing the scope releases
 * all of them in reverse order, so nothing outlives the component that
 * created it.
 *
 * Components get one automatically for `setup()`. Code outside a component
 * (tests, services, `index.php`) can open its own with `effectScope()`; a
 * composable called with no scope at all lives as long as the application.
 */
final class EffectScope implements Disposable
{
    /** @var list<self> */
    private static array $stack = [];

    /** @var list<Closure(): void> */
    private array $disposers = [];
    /** @var list<Closure(): void> */
    private array $mounted = [];
    /** @var list<Closure(): void> */
    private array $unmounted = [];
    /** @var list<Closure(): void> */
    private array $focus = [];
    /** @var list<Closure(): void> */
    private array $blur = [];
    private bool $disposed = false;
    private bool $isMounted = false;
    private bool $focused = false;

    /** The scope `setup()` or `effectScope()` is currently running in. */
    public static function current(): ?self
    {
        $index = array_key_last(self::$stack);

        return $index === null ? null : self::$stack[$index];
    }

    /** Throws when called outside setup(), naming the helper that needs one. */
    public static function require(string $helper): self
    {
        $scope = self::current();
        if ($scope === null) {
            throw new LogicException(
                "{$helper}() must be called inside a component setup() or an effectScope().",
            );
        }

        return $scope;
    }

    /**
     * Runs $callback with this scope as the current one.
     *
     * @template T
     * @param Closure(): T $callback
     * @return T
     */
    public function run(Closure $callback): mixed
    {
        if ($this->disposed) {
            throw new LogicException('A disposed effect scope cannot run code.');
        }
        self::$stack[] = $this;
        try {
            return $callback();
        } finally {
            if (array_pop(self::$stack) !== $this) {
                self::$stack = [];
                throw new LogicException('Effect scope stack is corrupted.');
            }
        }
    }

    /** Runs $cleanup when the scope is disposed (immediately when it already was). */
    public function onDispose(Closure $cleanup): void
    {
        if ($this->disposed) {
            $cleanup();

            return;
        }
        $this->disposers[] = $cleanup;
    }

    /**
     * @template T of Disposable
     * @param T $resource
     * @return T
     */
    public function own(Disposable $resource): Disposable
    {
        $this->onDispose(static function () use ($resource): void {
            $resource->dispose();
        });

        return $resource;
    }

    public function onMounted(Closure $hook): void
    {
        if ($this->isMounted) {
            $hook();

            return;
        }
        $this->mounted[] = $hook;
    }

    public function onUnmounted(Closure $hook): void
    {
        $this->unmounted[] = $hook;
    }

    public function onFocus(Closure $hook): void
    {
        $this->focus[] = $hook;
        if ($this->focused) {
            $hook();
        }
    }

    public function onBlur(Closure $hook): void
    {
        $this->blur[] = $hook;
    }

    /** True when the scope registered anything that disposal released. */
    public function holdsResources(): bool
    {
        return $this->disposers !== []
            || $this->mounted !== []
            || $this->unmounted !== []
            || $this->focus !== []
            || $this->blur !== [];
    }

    /** Number of pending cleanups, for leak diagnostics and tests. */
    public function size(): int
    {
        return count($this->disposers);
    }

    public function isMounted(): bool
    {
        return $this->isMounted;
    }

    public function isFocused(): bool
    {
        return $this->focused;
    }

    /** @internal The owning component was attached for the first time in this mount cycle. */
    public function mount(): void
    {
        if ($this->isMounted || $this->disposed) {
            return;
        }
        $this->isMounted = true;
        $hooks = $this->mounted;
        $this->mounted = [];
        foreach ($hooks as $hook) {
            $hook();
        }
    }

    /** @internal Visibility of the owning screen changed (navigation focus or blur). */
    public function focusChanged(bool $focused): void
    {
        if ($this->disposed || $focused === $this->focused) {
            return;
        }
        $this->focused = $focused;
        foreach ($focused ? $this->focus : $this->blur as $hook) {
            $hook();
        }
    }

    public function dispose(): void
    {
        if ($this->disposed) {
            return;
        }
        $failure = null;
        try {
            if ($this->focused) {
                $this->focusChanged(false);
            }
        } catch (Throwable $error) {
            $failure = $error;
        }
        if ($this->isMounted) {
            foreach ($this->unmounted as $hook) {
                try {
                    $hook();
                } catch (Throwable $error) {
                    $failure ??= $error;
                }
            }
        }
        $this->disposed = true;
        $disposers = array_reverse($this->disposers);
        $this->disposers = [];
        $this->mounted = [];
        $this->unmounted = [];
        $this->focus = [];
        $this->blur = [];
        foreach ($disposers as $cleanup) {
            try {
                $cleanup();
            } catch (Throwable $error) {
                $failure ??= $error;
            }
        }
        if ($failure !== null) {
            throw $failure;
        }
    }

    public function disposed(): bool
    {
        return $this->disposed;
    }
}
