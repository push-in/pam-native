<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use LogicException;
use Pam\Native\Internal\ComponentLifecycle;
use Pam\Native\Internal\PamPhpRegistry;
use WeakMap;

abstract class Component implements Renderable
{
    /** @var WeakMap<object, string>|null */
    private static ?WeakMap $persisted = null;

    /** @var array<string, Closure> */
    private array $pamEventListeners = [];

    /** @var array<string, list<Renderable>> */
    private array $pamSlots = [];

    public function render(): Renderable
    {
        return PamPhpRegistry::view($this);
    }

    public function boot(): void
    {
    }

    public function mount(): void
    {
    }

    public function rendered(): void
    {
    }

    public function attached(): void
    {
    }

    public function resumed(): void
    {
    }

    public function updated(string $property): void
    {
    }

    public function paused(): void
    {
    }

    public function unmount(): void
    {
    }

    final public function toElement(): Element
    {
        return ComponentLifecycle::render($this, function (): Element {
            if ($this instanceof Restorable) {
                $persisted = self::$persisted ??= new WeakMap();
                if (!isset($persisted[$this])) {
                    $state = State::get('component.'.$this->stateKey(), []);
                    $this->restoreState(is_array($state) ? $state : []);
                    $persisted[$this] = '';
                }
            }
            $rendered = $this->render();

            if ($rendered instanceof View) {
                $element = $rendered->withScope($this)->toElement();
            } else {
                if ($rendered === $this) {
                    throw new LogicException('A component cannot render itself.');
                }

                $element = $rendered->toElement();
            }

            if ($this instanceof Restorable) {
                $state = $this->saveState();
                $hash = hash('xxh3', serialize($state));
                $persisted = self::$persisted ??= new WeakMap();
                if (($persisted[$this] ?? '') !== $hash) {
                    State::set('component.'.$this->stateKey(), $state);
                    $persisted[$this] = $hash;
                }
            }

            return $element;
        });
    }

    /**
     * @param array<string, list<Renderable>> $slots
     * @param array<string, Closure> $listeners
     */
    final public function __pamConfigure(
        array $slots,
        array $listeners,
    ): void {
        $this->pamSlots = $slots;
        $this->pamEventListeners = $listeners;
    }

    /** @return array<string, list<Renderable>> */
    final public function __pamSlots(): array
    {
        return $this->pamSlots;
    }

    final public function __pamNotifyUpdated(string $property): void
    {
        $this->updated($property);
    }

    final protected function emit(string $event, mixed $payload = null): void
    {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/D', $event) !== 1) {
            throw new LogicException('Component event names must be safe identifiers.');
        }

        $listener = $this->pamEventListeners[$event] ?? null;

        if ($listener !== null) {
            if ($payload === null) {
                $listener();
            } else {
                $listener($payload);
            }
        }
    }
}
