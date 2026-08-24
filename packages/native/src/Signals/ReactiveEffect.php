<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

use Closure;
use WeakMap;

final class ReactiveEffect implements ReactiveObserver
{
    /** @var WeakMap<ReactiveSource, true> */
    private WeakMap $dependencies;
    private ?Closure $cleanup = null;
    private bool $running = false;
    private bool $stopped = false;

    /** @param Closure(): (Closure(): void)|null $run */
    public function __construct(private readonly Closure $run)
    {
        $this->dependencies = new WeakMap();
        $this->execute();
    }

    public function dependencyChanged(): void
    {
        if (!$this->running && !$this->stopped) {
            $this->execute();
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

    public function stop(): void
    {
        if ($this->stopped) {
            return;
        }
        $this->stopped = true;
        $this->cleanup?->__invoke();
        $this->cleanup = null;
        $this->clearDependencies();
    }

    private function execute(): void
    {
        $this->running = true;
        try {
            $this->cleanup?->__invoke();
            $this->clearDependencies();
            $cleanup = ReactiveScope::evaluate($this, $this->run);
            $this->cleanup = $cleanup instanceof Closure ? $cleanup : null;
        } finally {
            $this->running = false;
        }
    }

    private function clearDependencies(): void
    {
        foreach ($this->dependencies as $source => $_) {
            $source->detach($this);
        }
        $this->dependencies = new WeakMap();
    }
}
