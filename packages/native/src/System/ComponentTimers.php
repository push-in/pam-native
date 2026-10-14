<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use Pam\Native\AppState;
use Pam\Native\Internal\ComponentLifecycle;
use Pam\Native\Disposable;
use Pam\Native\Scheduling\Debouncer;
use Pam\Native\Scheduling\Throttler;

use function count;

/**
 * The timers of one component, returned by `$this->timers()`. Everything
 * created here stops on unmount; timeouts and intervals also follow their
 * TimerBackgroundPolicy (Suspend by default) while the app is in the
 * background, and are re-armed when the component resumes.
 *
 *     public function mount(): void
 *     {
 *         $this->timers()->every(30_000, fn () => $this->poll());
 *         $this->hideToast = $this->timers()->timeout(3_000, fn () => $this->toast = null);
 *         $this->search = $this->timers()->debounce(300, fn (string $q) => $this->search($q));
 *     }
 */
final class ComponentTimers
{
    /** @var array<int, ComponentTimer> */
    private array $timers = [];
    /** @var array<int, Disposable> */
    private array $helpers = [];
    private bool $suspended;

    /** @internal Created by Component::timers(). */
    public function __construct()
    {
        $this->suspended = ComponentLifecycle::currentAppState() === AppState::Background;
    }

    /** @param Closure(ComponentTimer): void $callback */
    public function timeout(
        int $milliseconds,
        Closure $callback,
        TimerBackgroundPolicy $background = TimerBackgroundPolicy::Suspend,
    ): ComponentTimer {
        if ($milliseconds < 0 || $milliseconds > 86_400_000) {
            throw new InvalidArgumentException('Timer delay must be between 0 and 86,400,000 milliseconds.');
        }

        return $this->start($milliseconds, null, $callback, $background);
    }

    /** @param Closure(ComponentTimer): void $callback */
    public function every(
        int $milliseconds,
        Closure $callback,
        TimerBackgroundPolicy $background = TimerBackgroundPolicy::Suspend,
    ): ComponentTimer {
        if ($milliseconds < 16 || $milliseconds > 86_400_000) {
            throw new InvalidArgumentException('Repeating timers need an interval between 16 and 86,400,000 milliseconds.');
        }

        return $this->start($milliseconds, $milliseconds, $callback, $background);
    }

    /** @param Closure(mixed...): void $action */
    public function debounce(int $milliseconds, Closure $action, ?int $maxWaitMs = null): Debouncer
    {
        return $this->keep(new Debouncer($milliseconds, $action, $maxWaitMs));
    }

    /** @param Closure(mixed...): void $action */
    public function throttle(int $milliseconds, Closure $action, bool $leading = true, bool $trailing = true): Throttler
    {
        return $this->keep(new Throttler($milliseconds, $action, $leading, $trailing));
    }

    /** Number of live timeouts and intervals. */
    public function count(): int
    {
        return count($this->timers);
    }

    public function suspended(): bool
    {
        return $this->suspended;
    }

    /** Cancels every timer, debouncer and throttler of the component. */
    public function cancelAll(): void
    {
        foreach ($this->timers as $timer) {
            $timer->cancel();
        }
        foreach ($this->helpers as $helper) {
            $helper->dispose();
        }
        $this->timers = [];
        $this->helpers = [];
    }

    /** @internal Component paused (background). */
    public function suspend(): void
    {
        $this->suspended = true;
        foreach ($this->timers as $timer) {
            $timer->suspend();
        }
    }

    /** @internal Component resumed (foreground). */
    public function resume(): void
    {
        $this->suspended = false;
        foreach ($this->timers as $timer) {
            $timer->resume();
        }
    }

    private function start(int $delay, ?int $interval, Closure $callback, TimerBackgroundPolicy $background): ComponentTimer
    {
        $timer = new ComponentTimer($delay, $interval, $callback, $background, function (ComponentTimer $timer): void {
            unset($this->timers[spl_object_id($timer)]);
        });
        $this->timers[spl_object_id($timer)] = $timer;
        $timer->start($this->suspended);

        return $timer;
    }

    /**
     * @template T of Disposable
     * @param T $helper
     * @return T
     */
    private function keep(Disposable $helper): Disposable
    {
        if (count($this->helpers) >= 64) {
            $this->helpers = array_filter($this->helpers, static fn (Disposable $kept): bool => !$kept->disposed());
        }
        $this->helpers[spl_object_id($helper)] = $helper;

        return $helper;
    }
}
