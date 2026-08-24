<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

use Closure;
use Pam\Native\Internal\DependencyTracker;
use WeakMap;

/** @template T */
final class Signal implements ReactiveSource
{
    /** @var WeakMap<ReactiveObserver, true> */
    private WeakMap $observers;
    /** @var array<int, Closure(T, T): void> */
    private array $listeners = [];
    private int $nextListener = 1;

    /** @param T $value */
    public function __construct(private mixed $value)
    {
        $this->observers = new WeakMap();
    }

    /** @return T */
    public function get(): mixed
    {
        DependencyTracker::read($this, 'value');
        ReactiveScope::track($this);
        return $this->value;
    }

    /** @param T $value */
    public function set(mixed $value): void
    {
        if ($value === $this->value) {
            return;
        }
        $previous = $this->value;
        $this->value = $value;
        DependencyTracker::invalidate($this, 'value');
        foreach ($this->observers as $observer => $_) {
            $observer->dependencyChanged();
        }
        foreach ($this->listeners as $listener) {
            $listener($value, $previous);
        }
        Signals::changed();
    }

    /** @param Closure(T): T $update */
    public function update(Closure $update): void
    {
        $this->set($update($this->value));
    }

    /** @param Closure(T, T): void $listener */
    public function subscribe(Closure $listener): int
    {
        $id = $this->nextListener++;
        $this->listeners[$id] = $listener;
        return $id;
    }

    public function unsubscribe(int $subscription): void
    {
        unset($this->listeners[$subscription]);
    }

    public function attach(ReactiveObserver $observer): void
    {
        $this->observers[$observer] = true;
    }

    public function detach(ReactiveObserver $observer): void
    {
        unset($this->observers[$observer]);
    }
}
