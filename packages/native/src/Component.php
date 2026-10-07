<?php

declare(strict_types=1);

namespace Pam\Native;

use BackedEnum;
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

use function array_key_exists;
use function count;
use function in_array;
use function is_array;
use function is_object;

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
    /** @var array<string, mixed>|null Own state captured after the last real render. */
    private ?array $pamSnapshot = null;
    private int $pamEpoch = -1;
    /** @var array<string, Closure> event handlers bound to this scope, by expression */
    private array $pamHandlers = [];

    /** @var array<class-string, bool> */
    private static array $pamAlwaysRender = [];

    /** @var array<class-string, array{0: int, 1: list<string>}> */
    private static array $pamObjectKeys = [];

    /** @var array<class-string, bool> */
    private static array $pamHasEffects = [];

    /** @internal True when the component's class declares effects or watchers. */
    final public static function __pamHasEffects(Component $component): bool
    {
        return self::$pamHasEffects[$component::class] ??= self::pamDeclaresEffects($component::class);
    }

    /** @param class-string $class */
    private static function pamDeclaresEffects(string $class): bool
    {
        return (new ReflectionMethod($class, 'effects'))->getDeclaringClass()->getName() !== self::class
            || (new ReflectionMethod($class, 'watchers'))->getDeclaringClass()->getName() !== self::class;
    }

    /** Component bookkeeping that never affects rendered output. */
    /** @var array<string, true>|null mangled names of PAM_SNAPSHOT_EXCLUDED */
    private static ?array $pamSnapshotExcludedKeys = null;

    private const PAM_SNAPSHOT_EXCLUDED = [
        'pamEventListeners',
        'pamEffects',
        'pamMemo',
        'pamComputed',
        'pamChanges',
        'pamFailureAttempt',
        'pamSkipRender',
        'pamLastElement',
        'pamSnapshot',
        'pamEpoch',
        'pamHandlers',
    ];

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

    /** Transient system UI (permission prompt, picker, share sheet, biometric dialog) is covering the still-visible app. */
    public function inactive(): void
    {
    }

    /** The transient system UI closed and the app is the foreground window again, without having been paused. */
    public function activated(): void
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
    final protected function pushRoute(string|BackedEnum $route, mixed ...$params): void
    {
        Navigation::push($route, $params);
    }

    /** @param string|int|float|bool|null ...$params */
    final protected function navigateRoute(string|BackedEnum $route, mixed ...$params): bool
    {
        return Navigation::navigate($route, $params);
    }

    /** @param string|int|float|bool|null ...$params */
    final protected function replaceRoute(string|BackedEnum $route, mixed ...$params): void
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
                DependencyTracker::read($component, '__pamProvided');

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
        ComponentLifecycle::enter($this);
        try {
            $element = $this->pamElement();
            $this->rendered();

            return $element;
        } finally {
            ComponentLifecycle::leave();
        }
    }

    private function pamElement(): Element
    {
            if (
                $this->pamLastElement !== null
                && ($this->pamSkipRender || $this->pamCanReuse())
            ) {
                $this->pamSkipRender = false;
                ComponentLifecycle::retainSubtree($this);

                return $this->pamLastElement;
            }
            ComponentLifecycle::beginComponentRender($this);
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
                $started = Profiler::start();
                try {
                    $rendered = $this->render();
                } finally {
                    Profiler::record('component.render', $started, ['component' => $this::class]);
                }

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
                $this->pamEpoch = DependencyTracker::epoch();
                $this->pamSnapshot = $this->pamOwnState();

                return $element;
            } finally {
                DependencyTracker::end($this);
            }
        }

    /**
     * @internal Reconfigures an instance whose call site evaluated exactly
     * the same inputs as last time: only the (fresh) listeners change. False
     * when the slots, parent or inherited styles differ.
     *
     * @param array<string, list<Renderable>> $slots
     * @param array<string, Closure> $listeners
     * @param array<string, mixed> $inheritedStyles
     */
    final public function __pamReconfigure(
        array $slots,
        array $listeners,
        ?Component $parent,
        array $inheritedStyles,
    ): bool {
        if (
            $slots !== $this->pamSlots
            || $parent !== $this->pamParent
            || $inheritedStyles !== $this->pamInheritedStyles
        ) {
            return false;
        }
        $this->pamEventListeners = $listeners;
        $provided = $this->provide();
        if ($provided !== $this->pamProvided) {
            DependencyTracker::invalidate($this, '__pamProvided');
        }
        $this->pamProvided = $provided;

        return true;
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
        $provided = $this->provide();
        if ($provided !== $this->pamProvided) {
            DependencyTracker::invalidate($this, '__pamProvided');
        }
        $this->pamProvided = $provided;
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

    /**
     * Requests a re-render of this component on the next frame even though
     * none of its properties changed (e.g. it reads a mutable service).
     */
    final protected function markForRender(): void
    {
        DependencyTracker::markDirty($this);
        Runtime::scheduleRender();
    }

    /** @internal True when own properties changed since the last real render. */
    final public function __pamStateChanged(): bool
    {
        return $this->pamSnapshot !== null && $this->pamSnapshot !== $this->pamOwnState();
    }

    private function pamCanReuse(): bool
    {
        if (
            $this->pamEpoch !== DependencyTracker::epoch()
            || !DependencyTracker::canSkip($this)
        ) {
            return false;
        }
        $class = static::class;
        $always = self::$pamAlwaysRender[$class] ??= self::pamAlwaysRenders($class);

        return !$always && $this->pamSnapshot === $this->pamOwnState();
    }

    /** @param class-string $class */
    private static function pamAlwaysRenders(string $class): bool
    {
        for ($reflection = new \ReflectionClass($class); $reflection !== false; $reflection = $reflection->getParentClass()) {
            if ($reflection->getAttributes(\Pam\Native\Attributes\AlwaysRender::class) !== []) {
                return true;
            }
        }

        return false;
    }

    /**
     * Shallow copy of every property (public, protected and private, including
     * subclasses) except render bookkeeping. Arrays compare by value and
     * objects by identity.
     *
     * @return array<string, mixed>
     */
    private function pamOwnState(): array
    {
        $values = array_diff_key((array) $this, self::$pamSnapshotExcludedKeys ??= array_fill_keys(
            array_map(
                static fn (string $name): string => "\0".self::class."\0".$name,
                self::PAM_SNAPSHOT_EXCLUDED,
            ),
            true,
        ));
        $plan = self::$pamObjectKeys[static::class] ??= self::pamObjectKeys(static::class);
        if (count($values) > $plan[0]) {
            // Dynamic properties: any value may hold an object.
            return self::pamExpandObjects($values, 0);
        }
        // Only properties whose declared type admits an object can need
        // expanding; scalar and array properties compare as they are.
        foreach ($plan[1] as $key) {
            $value = $values[$key] ?? null;
            if (
                is_object($value)
                && !$value instanceof Component
                && !$value instanceof Closure
                && !$value instanceof \UnitEnum
                && !$value instanceof Element
                && !$value instanceof ComponentState
            ) {
                $values[$key] = [$value, self::pamExpandObjects((array) $value, 1)];
            }
        }

        return $values;
    }

    /**
     * Declared instance property count and the mangled array keys of the
     * properties whose type admits an object.
     *
     * @param class-string $class
     * @return array{0: int, 1: list<string>}
     */
    private static function pamObjectKeys(string $class): array
    {
        $declared = [];
        $objects = [];
        for ($reflection = new \ReflectionClass($class); $reflection !== false; $reflection = $reflection->getParentClass()) {
            $name = $reflection->getName();
            foreach ($reflection->getProperties() as $property) {
                if ($property->isStatic() || $property->getDeclaringClass()->getName() !== $name) {
                    continue;
                }
                $key = match (true) {
                    $property->isPrivate() => "\0".$name."\0".$property->getName(),
                    $property->isProtected() => "\0*\0".$property->getName(),
                    default => $property->getName(),
                };
                if (isset($declared[$key])) {
                    continue;
                }
                $declared[$key] = true;
                if (self::pamTypeAdmitsObject($property->getType())) {
                    $objects[] = $key;
                }
            }
        }

        $excluded = self::$pamSnapshotExcludedKeys ?? [];
        // Framework references compared by identity: the parent component and
        // the local state (whose every change bumps pamRevision).
        $identity = ["\0".self::class."\0pamParent" => true, "\0".self::class."\0pamState" => true];
        $objects = array_values(array_filter($objects, static fn (string $key): bool => !isset($identity[$key])));
        $objects = array_values(array_filter($objects, static fn (string $key): bool => !isset($excluded[$key])));

        return [count(array_diff_key($declared, $excluded)), $objects];
    }

    private static function pamTypeAdmitsObject(?\ReflectionType $type): bool
    {
        if ($type === null) {
            return true;
        }
        if ($type instanceof \ReflectionNamedType) {
            return !$type->isBuiltin()
                || in_array($type->getName(), ['object', 'mixed', 'iterable', 'callable'], true);
        }
        if ($type instanceof \ReflectionUnionType || $type instanceof \ReflectionIntersectionType) {
            foreach ($type->getTypes() as $member) {
                if (self::pamTypeAdmitsObject($member)) {
                    return true;
                }
            }

            return false;
        }

        return true;
    }

    /**
     * Plain objects held by a component (services, view models, records) are
     * captured with their own properties, two levels deep, so in-place
     * mutations such as `$this->model->draft = ...` are detected. Components
     * track themselves; closures, enums and elements compare by identity.
     *
     * @param array<array-key, mixed> $values
     * @return array<array-key, mixed>
     */
    private static function pamExpandObjects(array $values, int $depth): array
    {
        foreach ($values as $key => $value) {
            if (
                is_object($value)
                && !$value instanceof Component
                && !$value instanceof Closure
                && !$value instanceof \UnitEnum
                && !$value instanceof Element
            ) {
                $values[$key] = [
                    $value,
                    $depth < 1 ? self::pamExpandObjects((array) $value, $depth + 1) : (array) $value,
                ];
            }
        }

        return $values;
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
        $class = static::class;
        // Components that override neither effects() nor watchers() have none.
        if (!(self::$pamHasEffects[$class] ??= self::pamDeclaresEffects($class)) && $this->pamEffects === []) {
            return;
        }
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

    /**
     * @internal Drops the render references of an instance the registry
     * discarded (its last element tree, slots, listeners and parent), so the
     * reference cycles through event closures are freed at once instead of
     * waiting for the cycle collector.
     */
    /** @internal An event handler closure this scope already built. */
    final public function __pamHandler(string $key): ?Closure
    {
        return $this->pamHandlers[$key] ?? null;
    }

    /** @internal */
    final public function __pamRememberHandler(string $key, Closure $handler): Closure
    {
        if (count($this->pamHandlers) >= 256) {
            $this->pamHandlers = [];
        }

        return $this->pamHandlers[$key] = $handler;
    }

    final public function __pamRelease(): void
    {
        $this->pamHandlers = [];
        $this->pamLastElement = null;
        $this->pamSnapshot = null;
        $this->pamEventListeners = [];
        $this->pamSlots = [];
        $this->pamParent = null;
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
        // A weak reference keeps the state's callback from forming a cycle
        // with its component, so a discarded component is freed at once.
        $owner = \WeakReference::create($this);

        return $this->pamState ??= new ComponentState(
            $this->initialState(),
            static function (string $name, mixed $current, mixed $previous) use ($owner): void {
                $component = $owner->get();
                if ($component === null) {
                    return;
                }
                $component->pamRevision++;
                $component->pamComputed = [];
                $component->pamSkipRender = false;
                $component->pamChanges['state.'.$name] = [
                    'previous' => $previous,
                    'current' => $current,
                ];
                Runtime::scheduleRender();
            },
        );
    }
}
