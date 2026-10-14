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
 */
final class ComponentTimer implements Disposable
{
    private ?TimerHandle $native = null;
    private bool $finished = false;
    private bool $suspended = false;
    private float $deadlineMs = 0.0;
    private ?Closure $onFinish;

    /**
     * @internal Created by ComponentTimers.
     * @param Closure(ComponentTimer): void $callback
     * @param Closure(ComponentTimer): void $onFinish
     */
    public function __construct(
        private readonly int $delayMs,
        private readonly ?int $intervalMs,
        private readonly Closure $callback,
        public readonly TimerBackgroundPolicy $backgroundPolicy,
        Closure $onFinish,
    ) {
        $this->onFinish = $onFinish;
    }

    public function cancel(): void
    {
        if ($this->finished) {
            return;
        }
        $this->disarm();
        $this->finish();
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
            $this->suspend();

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
            });

            return;
        }
        $this->native = Timers::timeout($delayMs, function (): void {
            if ($this->finished || $this->suspended) {
                return;
            }
            $this->native = null;
            $this->finish();
            ($this->callback)($this);
        });
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
        $onFinish = $this->onFinish;
        $this->onFinish = null;
        $onFinish?->__invoke($this);
    }

    private static function nowMs(): float
    {
        return hrtime(true) / 1_000_000;
    }
}
