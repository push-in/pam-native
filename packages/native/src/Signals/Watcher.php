<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

use Closure;
use Pam\Native\Disposable;

/**
 * Runs a callback whenever a reactive source changes, Vue `watch()` style:
 * `$callback($next, $previous)`. The source is a signal, a computed value or
 * a closure reading signals. Created by `watch()`; stopped by dispose(), or
 * automatically when the setup() scope that created it is disposed.
 */
final class Watcher implements Disposable
{
    private ?ReactiveEffect $effect = null;
    private bool $initialized = false;
    private mixed $previous = null;
    private bool $disposed = false;

    /**
     * @param ReadableSignal<mixed>|Closure(): mixed $source
     * @param Closure(mixed, mixed): void $callback
     */
    public function __construct(ReadableSignal|Closure $source, Closure $callback, bool $immediate = false)
    {
        $read = $source instanceof ReadableSignal ? static fn (): mixed => $source->get() : $source;
        $this->effect = new ReactiveEffect(function () use ($read, $callback, $immediate): void {
            $current = $read();
            if (!$this->initialized) {
                $this->initialized = true;
                $this->previous = $current;
                if ($immediate) {
                    ReactiveScope::untracked(static fn () => $callback($current, null));
                }

                return;
            }
            if ($current === $this->previous) {
                return;
            }
            $previous = $this->previous;
            $this->previous = $current;
            ReactiveScope::untracked(static fn () => $callback($current, $previous));
        });
    }

    public function dispose(): void
    {
        if ($this->disposed) {
            return;
        }
        $this->disposed = true;
        $this->effect?->stop();
        $this->effect = null;
        $this->previous = null;
    }

    public function disposed(): bool
    {
        return $this->disposed;
    }
}
