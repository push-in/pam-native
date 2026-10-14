<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

use Closure;
use InvalidArgumentException;
use Pam\Native\Disposable;

/**
 * Runs an action at most once per $intervalMs. With $leading the first call
 * runs at once; with $trailing the last call made during the window runs
 * when it closes, so the final value is never lost (scroll position, typing
 * indicator, progress reports).
 *
 *     $typing = new Throttler(3_000, fn () => $this->sendTyping());
 *     $typing->call();
 */
final class Throttler implements Disposable
{
    /** @var (Closure(): void)|null cancels the armed timer */
    private ?Closure $window = null;
    /** @var Closure(int, Closure(): void): (Closure(): void) */
    private Closure $schedule;
    /** @var list<mixed>|null */
    private ?array $trailingArguments = null;
    private bool $disposed = false;

    /**
     * @param Closure(mixed...): void $action
     * @param (Closure(int, Closure(): void): (Closure(): void))|null $schedule
     *        arms a timer and returns its canceller; defaults to native Timers (test seam)
     */
    public function __construct(
        public readonly int $intervalMs,
        private readonly Closure $action,
        public readonly bool $leading = true,
        public readonly bool $trailing = true,
        ?Closure $schedule = null,
    ) {
        if ($intervalMs < 1 || (!$leading && !$trailing)) {
            throw new InvalidArgumentException('Throttle interval must be positive and leading or trailing must be enabled.');
        }
        $this->schedule = $schedule ?? Debouncer::nativeSchedule(...);
    }

    public function call(mixed ...$arguments): void
    {
        if ($this->disposed) {
            return;
        }
        if ($this->window === null) {
            if ($this->leading) {
                $this->open();
                ($this->action)(...$arguments);

                return;
            }
            $this->trailingArguments = array_values($arguments);
            $this->open();

            return;
        }
        if ($this->trailing) {
            $this->trailingArguments = array_values($arguments);
        }
    }

    /** True while calls are being held back. */
    public function throttling(): bool
    {
        return $this->window !== null;
    }

    /** Closes the window and drops a pending trailing call. */
    public function cancel(): void
    {
        $cancel = $this->window;
        $this->window = null;
        $this->trailingArguments = null;
        $cancel?->__invoke();
    }

    public function dispose(): void
    {
        $this->cancel();
        $this->disposed = true;
    }

    public function disposed(): bool
    {
        return $this->disposed;
    }

    private function open(): void
    {
        $this->window = ($this->schedule)($this->intervalMs, function (): void {
            $this->window = null;
            $arguments = $this->trailingArguments;
            $this->trailingArguments = null;
            if ($arguments === null || $this->disposed) {
                return;
            }
            $this->open();
            ($this->action)(...$arguments);
        });
    }
}
