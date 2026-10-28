<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

use Closure;
use WeakMap;

/**
 * @template T
 * @implements ReadableSignal<T>
 * @property-read T $value reactive alias of get()
 */
final class ComputedSignal implements ReactiveObserver, ReactiveSource, ReadableSignal
{
    /** @var WeakMap<ReactiveSource, true> */
    private WeakMap $dependencies;
    /** @var WeakMap<ReactiveObserver, true> */
    private WeakMap $observers;
    private bool $dirty = true;
    /** @var T|null */
    private mixed $value = null;

    /** @param Closure(): T $compute */
    public function __construct(private readonly Closure $compute)
    {
        $this->dependencies = new WeakMap();
        $this->observers = new WeakMap();
    }

    /** @return T */
    public function get(): mixed
    {
        ReactiveScope::track($this);
        if ($this->dirty) {
            $this->clearDependencies();
            $this->value = ReactiveScope::evaluate($this, $this->compute);
            $this->dirty = false;
        }
        return $this->value;
    }

    /** @return T */
    public function peek(): mixed
    {
        if ($this->dirty) {
            $this->clearDependencies();
            $this->value = ReactiveScope::evaluate($this, $this->compute);
            $this->dirty = false;
        }

        return $this->value;
    }

    public function __get(string $name): mixed
    {
        if ($name !== 'value') {
            throw new \LogicException("Unknown computed property {$name}; use ->value.");
        }

        return $this->get();
    }

    public function __set(string $name, mixed $value): void
    {
        throw new \LogicException('A computed value is read-only.');
    }

    public function __isset(string $name): bool
    {
        return $name === 'value';
    }

    public function dependencyChanged(): void
    {
        if ($this->dirty) {
            return;
        }
        $this->dirty = true;
        $this->notifyObservers();
    }

    public function dependOn(ReactiveSource $source): void
    {
        if (isset($this->dependencies[$source])) {
            return;
        }
        $this->dependencies[$source] = true;
        $source->attach($this);
    }

    public function attach(ReactiveObserver $observer): void
    {
        $this->observers[$observer] = true;
    }

    public function detach(ReactiveObserver $observer): void
    {
        unset($this->observers[$observer]);
    }

    private function clearDependencies(): void
    {
        foreach ($this->dependencies as $source => $_) {
            $source->detach($this);
        }
        $this->dependencies = new WeakMap();
    }

    /**
     * Notifies a snapshot of the observers. Each effect detaches and re-attaches
     * itself while it re-runs, which moves it within the WeakMap; iterating the
     * live map then skipped the next observer. An observer detached by an
     * earlier one in the same pass (a disposed watcher) is not notified.
     */
    private function notifyObservers(): void
    {
        $observers = [];
        foreach ($this->observers as $observer => $_) {
            $observers[] = $observer;
        }
        foreach ($observers as $observer) {
            if (isset($this->observers[$observer])) {
                $observer->dependencyChanged();
            }
        }
    }
}
