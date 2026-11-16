<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Component;
use Pam\Native\System\ComponentTimer;
use Pam\Native\System\ComponentTimers;

/**
 * The one timer that renders a component again when a native clock label it
 * rendered changes length ({@see NativeTime::clockLayout()}), so the engine
 * measures the new label. It is armed for the earliest instant of the
 * component's last render and re-armed only when that instant moves; it is
 * suspended in the background (the render on return corrects the label),
 * cancelled on unmount and kept apart from the component's own timers().
 *
 * @internal
 */
final class ClockRemeasure
{
    private readonly ComponentTimers $timers;
    private ?ComponentTimer $timer = null;
    private ?int $dueMs = null;

    public function __construct()
    {
        $this->timers = new ComponentTimers();
    }

    /**
     * Arms the render for [dueMs] (epoch ms), or cancels it with null.
     * [merge] keeps an earlier instant already armed.
     */
    public function schedule(Component $component, ?int $dueMs, bool $merge = false): void
    {
        $armed = $this->timer !== null && $this->timer->active();
        if ($armed && ($dueMs === $this->dueMs || ($merge && ($dueMs === null || $this->dueMs <= $dueMs)))) {
            return;
        }
        $this->timer?->cancel();
        $this->timer = null;
        $this->dueMs = null;
        if ($dueMs === null) {
            return;
        }
        $owner = \WeakReference::create($component);
        $this->dueMs = $dueMs;
        $this->timer = $this->timers->timeout(
            max(0, $dueMs - NativeTime::nowMs()),
            static function () use ($owner): void {
                $component = $owner->get();
                if ($component === null) {
                    return;
                }
                DependencyTracker::markDirty($component);
                Runtime::scheduleRender();
            },
        );
    }

    /** Instant the render is armed for, null when none is pending. */
    public function due(): ?int
    {
        return $this->timer !== null && $this->timer->active() ? $this->dueMs : null;
    }

    public function suspended(): bool
    {
        return $this->timer !== null && $this->timer->suspended();
    }

    public function suspend(): void
    {
        $this->timers->suspend();
    }

    public function resume(): void
    {
        $this->timers->resume();
    }

    public function cancel(): void
    {
        $this->timers->cancelAll();
        $this->timer = null;
        $this->dueMs = null;
    }
}
