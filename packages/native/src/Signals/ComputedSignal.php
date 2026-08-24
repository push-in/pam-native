<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

use Closure;
use WeakMap;

/** @template T */
final class ComputedSignal implements ReactiveObserver, ReactiveSource
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

    public function dependencyChanged(): void
    {
        if ($this->dirty) {
            return;
        }
        $this->dirty = true;
        foreach ($this->observers as $observer => $_) {
            $observer->dependencyChanged();
        }
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
}
