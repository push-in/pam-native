<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use LogicException;
use Pam\Native\Attributes\Computed;
use Pam\Native\Internal\ComponentLifecycle;
use Pam\Native\Internal\PamPhpRegistry;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\DependencyTracker;
use ReflectionMethod;
use Throwable;
use WeakMap;
use Pam\Native\Diagnostics\Profiler;
use Pam\Native\Routing\Navigation;

abstract class Component implements Renderable
{
    /** @var WeakMap<object, string>|null */
    private static ?WeakMap $persisted = null;

    /** @var array<string, Closure> */
    private array $pamEventListeners = [];

    /** @var array<string, list<Renderable>> */
    private array $pamSlots = [];

    /** @var array<string, mixed> */
    private array $pamInheritedStyles = [];

    private ?ComponentState $pamState = null;
    private ?Component $pamParent = null;
    /** @var array<class-string, mixed> */
    private array $pamProvided = [];
    /** @var array<string, array{dependencies: string, cleanup: Closure|null, ran: bool}> */
    private array $pamEffects = [];
    /** @var array<string, array{dependencies: string, value: mixed}> */
    private array $pamMemo = [];
    /** @var array<string, array{revision: int, value: mixed}> */
    private array $pamComputed = [];
    /** @var array<string, array{previous: mixed, current: mixed}> */
    private array $pamChanges = [];
    private int $pamRevision = 0;
    private int $pamFailureAttempt = 0;
    private bool $pamSkipRender = false;
    private ?Element $pamLastElement = null;

    public function render(): Renderable
    {
        return PamPhpRegistry::view($this);
    }

    public function boot(): void
    {
    }

    public function setup(): void
    {
    }

    public function mount(): void
    {
    }

    public function rendered(): void
    {
    }

    /**
     * Marks an explicit restoration as authoritative so the first render does
     * not overwrite it with a second lookup from the component state store.
     */
    final protected function stateWasRestored(): void
    {
        $persisted = self::$persisted ??= new WeakMap();
        $persisted[$this] = '';
    }

    public function rendering(): void
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

    public function updating(string $property, mixed $next, mixed $previous): void
    {
    }

    public function propsChanged(ComponentChanges $changes): void
    {
    }

    public function shouldUpdate(ComponentChanges $changes): bool
    {
        return true;
    }

    public function paused(): void
    {
    }

    public function unmount(): void
    {
    }

    public function cleanup(): void
    {
    }

    /** @return array<string, mixed> */
    protected function initialState(): array
    {
        return [];
    }

    /** @return list<Effect> */
    protected function effects(): array
    {
        return [];
    }

    /** @return list<Effect> */
    protected function watchers(): array
    {
        return [];
    }

    /** @return array<string, Slot> */
    protected function slots(): array
    {
        return [];
    }

    /** @return array<class-string, mixed> */
    protected function provide(): array
    {
        return [];
    }

    /** @return list<class-string<ComponentEvent>> */
    protected function events(): array
    {
        return [];
    }

    public function failed(Throwable $error, ErrorContext $context): ?Renderable
    {
        return null;
    }

    public function fallback(): ?Renderable
    {
        return null;
    }

    /** @param string|int|float|bool|null ...$params */
    final protected function pushRoute(string $route, mixed ...$params): void
    {
        Navigation::push($route, $params);
    }

    /** @param string|int|float|bool|null ...$params */
    final protected function navigateRoute(string $route, mixed ...$params): bool
    {
        return Navigation::navigate($route, $params);
    }

    /** @param string|int|float|bool|null ...$params */
    final protected function replaceRoute(string $route, mixed ...$params): void
    {
        Navigation::replace($route, $params);
    }

    final protected function popRoute(): bool
    {
        return Navigation::back();
    }

    final public function __get(string $name): mixed
    {
        if ($name === 'state') {
            return $this->pamLocalState();
        }
        if (method_exists($this, $name)) {
            $method = new ReflectionMethod($this, $name);
            if ($method->getAttributes(Computed::class) !== []) {
                $cached = $this->pamComputed[$name] ?? null;
                if ($cached !== null && $cached['revision'] === $this->pamRevision) {
                    return $cached['value'];
                }
                $value = $method->invoke($this);
                $this->pamComputed[$name] = ['revision' => $this->pamRevision, 'value' => $value];

                return $value;
            }
        }

        throw new LogicException("Unknown component property {$name}.");
    }

    final protected function memo(string $key, array $dependencies, Closure $compute): mixed
    {
        $fingerprint = hash('xxh3', serialize($dependencies));
        $cached = $this->pamMemo[$key] ?? null;
        if ($cached === null || $cached['dependencies'] !== $fingerprint) {
            $cached = ['dependencies' => $fingerprint, 'value' => $compute()];
            $this->pamMemo[$key] = $cached;
        }

        return $cached['value'];
    }

    /** @template T @param class-string<T> $type @return T */
    final protected function inject(string $type): mixed
    {
        $component = $this->pamParent;
        while ($component !== null) {
            if (array_key_exists($type, $component->pamProvided)) {
                return $component->pamProvided[$type];
            }
            $component = $component->pamParent;
        }

        throw new LogicException("No provider found for {$type}.");
    }

    final protected function exposeTo(ComponentRef $ref): void
    {
        $ref->attach($this);
    }

    /** @return list<Renderable> */
    final protected function slot(string $name = 'slot'): array
    {
        return $this->pamSlots[$name] ?? [];
    }

    final public function toElement(): Element
    {
        return ComponentLifecycle::render($this, function (): Element {
            if (
                ($this->pamSkipRender || DependencyTracker::canSkip($this))
                && $this->pamLastElement !== null
            ) {
                $this->pamSkipRender = false;
                PamPhpRegistry::retainScope($this);

                return $this->pamLastElement;
            }
            DependencyTracker::begin($this);
            try {
                $this->rendering();
            if ($this instanceof Restorable) {
                $persisted = self::$persisted ??= new WeakMap();
                if (!isset($persisted[$this])) {
                    $state = State::get('component.'.$this->stateKey(), []);
                    $this->restoreState(is_array($state) ? $state : []);
                    $persisted[$this] = '';
                }
            }
            try {
                $rendered = Profiler::measure(
                    'component.render',
                    fn (): Renderable => $this->render(),
                    ['component' => $this::class],
                );

                if ($rendered instanceof View) {
                    $element = $rendered->withScope($this)->toElement();
                } else {
                    if ($rendered === $this) {
                        throw new LogicException('A component cannot render itself.');
                    }

                    $element = $rendered->toElement();
                }
                $this->pamFailureAttempt = 0;
            } catch (Throwable $error) {
                $this->pamFailureAttempt++;
                $recovery = $this->failed(
                    $error,
                    new ErrorContext($this::class, 'render', $this->pamFailureAttempt),
                );
                if ($recovery === null) {
                    $recovery = $this->fallback();
                }
                if ($recovery === null) {
                    throw $error;
                }
                $element = $recovery->toElement();
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

                $this->pamLastElement = $element;

                return $element;
            } finally {
                DependencyTracker::end($this);
            }
        });
    }

    /**
     * @param array<string, list<Renderable>> $slots
     * @param array<string, Closure> $listeners
     * @param array<string, mixed> $inheritedStyles
     */
    final public function __pamConfigure(
        array $slots,
        array $listeners,
        ?Component $parent = null,
        array $inheritedStyles = [],
    ): void {
        $this->pamSlots = $slots;
        $this->pamEventListeners = $listeners;
        $this->pamParent = $parent;
        $this->pamInheritedStyles = $inheritedStyles;
        $this->pamProvided = $this->provide();
        foreach ($this->slots() as $name => $definition) {
            if (!$definition instanceof Slot) {
                throw new LogicException('Component slot definitions must contain Slot instances.');
            }
            $count = count($slots[$name] ?? []);
            if ($count < $definition->minimum || ($definition->maximum !== null && $count > $definition->maximum)) {
                throw new LogicException("Slot {$name} received {$count} children.");
            }
        }
    }

    /** @return array<string, list<Renderable>> */
    final public function __pamSlots(): array
    {
        return $this->pamSlots;
    }

    /** @return array<string, mixed> */
    final public function __pamInheritedStyles(): array
    {
        return $this->pamInheritedStyles;
    }

    final public function __pamNotifyUpdating(string $property, mixed $next, mixed $previous): void
    {
        $this->updating($property, $next, $previous);
        DependencyTracker::markDirty($this);
        $this->pamChanges[$property] = ['previous' => $previous, 'current' => $next];
    }

    final public function __pamNotifyUpdated(string $property): void
    {
        $this->pamRevision++;
        $this->pamComputed = [];
        $this->updated($property);
    }

    final public function __pamFlushChanges(): void
    {
        if ($this->pamChanges === []) {
            return;
        }
        $changes = new ComponentChanges($this->pamChanges);
        $this->pamChanges = [];
        $this->propsChanged($changes);
        $this->pamSkipRender = !$this->shouldUpdate($changes);
    }

    final public function __pamSetup(): void
    {
        $this->pamLocalState();
        $this->setup();
    }

    final public function __pamRunEffects(): void
    {
        foreach ([...$this->effects(), ...$this->watchers()] as $index => $effect) {
            if (!$effect instanceof Effect) {
                throw new LogicException('Component effects must contain Effect instances.');
            }
            $key = (string) $index;
            $dependencyValue = ($effect->dependencies)();
            $dependencies = hash('xxh3', serialize($dependencyValue));
            $state = $this->pamEffects[$key] ?? ['dependencies' => '', 'cleanup' => null, 'ran' => false];
            if (($effect->once && $state['ran']) || (!$effect->once && $state['dependencies'] === $dependencies)) {
                continue;
            }
            $state['cleanup']?->__invoke();
            $cleanup = ($effect->run)($dependencyValue);
            $state = [
                'dependencies' => $dependencies,
                'cleanup' => $cleanup instanceof Closure ? $cleanup : null,
                'ran' => true,
            ];
            $this->pamEffects[$key] = $state;
        }
    }

    final public function __pamCleanup(): void
    {
        $failure = null;
        foreach ($this->pamEffects as $effect) {
            try {
                $effect['cleanup']?->__invoke();
            } catch (Throwable $error) {
                $failure ??= $error;
            }
        }
        $this->pamEffects = [];
        $this->pamMemo = [];
        $this->pamComputed = [];
        try {
            $this->cleanup();
        } catch (Throwable $error) {
            $failure ??= $error;
        }
        if ($failure !== null) {
            throw $failure;
        }
    }

    final protected function emit(string|ComponentEvent $event, mixed $payload = null): void
    {
        if ($event instanceof ComponentEvent) {
            $allowed = $this->events();
            if ($allowed !== [] && !in_array($event::class, $allowed, true)) {
                throw new LogicException('Typed component event '.$event::class.' is not declared.');
            }
            $payload = $event->payload();
            $event = $event->name();
        }
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

    private function pamLocalState(): ComponentState
    {
        return $this->pamState ??= new ComponentState(
            $this->initialState(),
            function (string $name, mixed $current, mixed $previous): void {
                $this->pamRevision++;
                $this->pamComputed = [];
                $this->pamSkipRender = false;
                $this->pamChanges['state.'.$name] = [
                    'previous' => $previous,
                    'current' => $current,
                ];
                Runtime::requestRender();
            },
        );
    }
}
