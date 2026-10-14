<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Disposable;

/**
 * Timer owned by a component, created by `$this->timers()->timeout()` or
 * `->every()`. It is cancelled when the component unmounts and follows its
 * TimerBackgroundPolicy while the app is in the background, so no hidden
 * screen keeps waking the CPU.
 *
 * A field holding a timer keeps the object after it fires or is cancelled
 * (including the automatic cancel on unmount): test `$timer?->active()`
 * rather than `=== null`, call `restart()` to arm it again, or let
 * `ComponentTimers::ensure()` reuse a live one.
 */
final class ComponentTimer implements Disposable
{
    private ?TimerHandle $native = null;
    private bool $finished = false;
    private bool $suspended = false;
    private float $deadlineMs = 0.0;
    /** @var list<Closure(ComponentTimer): void> */
    private array $cancelListeners = [];
    /** @var list<Closure(ComponentTimer): void> */
    private array $finishListeners = [];

    /** Longest single native arm; longer delays are scheduled in steps. */
    private const MAX_STEP_MS = 86_400_000;

    /**
     * @internal Created by ComponentTimers.
     * @param Closure(ComponentTimer): void $callback
     * @param Closure(ComponentTimer): bool $register adds the timer to its component; returns whether it starts suspended
     * @param Closure(ComponentTimer): void $unregister
     * @param (Closure(ComponentTimer): void)|null $onCancel
     * @param (Closure(string): void)|null $failure the native timer could not be armed
     */
    public function __construct(
        private readonly int $delayMs,
        private readonly ?int $intervalMs,
        private readonly Closure $callback,
        public readonly TimerBackgroundPolicy $backgroundPolicy,
        private readonly Closure $register,
        private readonly Closure $unregister,
        private readonly ?Closure $onCancel = null,
        private readonly ?Closure $failure = null,
    ) {
    }

    /** Stops the timer; the onCancel callback runs once. */
    public function cancel(): void
    {
        if ($this->finished) {
            return;
        }
        $this->disarm();
        $this->finish();
        $this->onCancel?->__invoke($this);
        foreach ($this->cancelListeners as $listener) {
            $listener($this);
        }
    }

    /** Adds a listener for cancellation (explicit, unmount, Cancel policy); never on fire. */
    public function onCancel(Closure $listener): self
    {
        $this->cancelListeners[] = $listener;

        return $this;
    }

    /** Adds a listener for the end of the timer for any reason: fired, cancelled or failed. */
    public function onFinish(Closure $listener): self
    {
        $this->finishListeners[] = $listener;

        return $this;
    }

    /**
     * Arms the timer again with its original delay and callback, whether it
     * is running, fired or cancelled. Restarting is not a cancellation.
     */
    public function restart(): self
    {
        $this->disarm();
        if (!$this->finished) {
            ($this->unregister)($this);
        }
        $this->finished = false;
        $this->suspended = false;
        $this->start(($this->register)($this));

        return $this;
    }

    /** True until the timeout fires or the timer is cancelled. */
    public function active(): bool
    {
        return !$this->finished;
    }

    /** True while waiting for the app to return to the foreground. */
    public function suspended(): bool
    {
        return $this->suspended && !$this->finished;
    }

    public function repeating(): bool
    {
        return $this->intervalMs !== null;
    }

    public function delayMs(): int
    {
        return $this->delayMs;
    }

    public function dispose(): void
    {
        $this->cancel();
    }

    public function disposed(): bool
    {
        return $this->finished;
    }

    /** @internal */
    public function start(bool $suspended): void
    {
        $this->deadlineMs = self::nowMs() + $this->delayMs;
        if ($suspended && $this->backgroundPolicy !== TimerBackgroundPolicy::Continue) {
            if ($this->backgroundPolicy === TimerBackgroundPolicy::Cancel) {
                $this->cancel();

                return;
            }
            $this->suspended = true;

            return;
        }
        $this->arm($this->delayMs);
    }

    /** @internal The app went to the background. */
    public function suspend(): void
    {
        if ($this->finished || $this->suspended) {
            return;
        }
        if ($this->backgroundPolicy === TimerBackgroundPolicy::Continue) {
            return;
        }
        if ($this->backgroundPolicy === TimerBackgroundPolicy::Cancel) {
            $this->cancel();

            return;
        }
        $this->disarm();
        $this->suspended = true;
    }

    /** @internal The app returned to the foreground. */
    public function resume(): void
    {
        if ($this->finished || !$this->suspended) {
            return;
        }
        $this->suspended = false;
        if ($this->intervalMs !== null) {
            $this->arm($this->intervalMs);

            return;
        }
        $this->armRemaining();
    }

    /** Arms the time left until the deadline, in steps of at most one day. */
    private function armRemaining(): void
    {
        $this->arm((int) max(0, ceil($this->deadlineMs - self::nowMs())));
    }

    private function arm(int $delayMs): void
    {
        if ($this->intervalMs !== null) {
            // Intervals restart a full period after start or resume.
            $this->native = Timers::every($this->intervalMs, function (): void {
                if (!$this->finished && !$this->suspended) {
                    ($this->callback)($this);
                }
            }, $this->failed(...));

            return;
        }
        $step = min($delayMs, self::MAX_STEP_MS);
        $partial = $delayMs > self::MAX_STEP_MS;
        $this->native = Timers::timeout($step, function () use ($partial): void {
            if ($this->finished || $this->suspended) {
                return;
            }
            $this->native = null;
            if ($partial) {
                // One step of a delay longer than a day.
                $this->armRemaining();

                return;
            }
            $this->finish();
            ($this->callback)($this);
        }, $this->failed(...));
    }

    /** The native timer failed: the timer ends; without a failure handler the error is rethrown. */
    private function failed(string $error): void
    {
        $this->native = null;
        if (!$this->finished) {
            $this->finish();
        }
        if ($this->failure === null) {
            throw new \RuntimeException($error);
        }
        ($this->failure)($error);
    }

    private function disarm(): void
    {
        $native = $this->native;
        $this->native = null;
        $native?->cancel();
    }

    private function finish(): void
    {
        $this->finished = true;
        $this->suspended = false;
        ($this->unregister)($this);
        foreach ($this->finishListeners as $listener) {
            $listener($this);
        }
    }

    /**
     * Wall-clock milliseconds: unlike the monotonic clock it keeps counting
     * while the device sleeps, so a suspended timeout resumes with the time
     * really left.
     */
    private static function nowMs(): float
    {
        return microtime(true) * 1_000;
    }
}
