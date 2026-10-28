<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use Pam\Native\AppState;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\HotReload\ComponentStatePreserver;
use WeakMap;

use function count;

final class ComponentLifecycle
{
    /** @var WeakMap<Component, ComponentLifecycleState>|null */
    private static ?WeakMap $states = null;

    private static int $pass = 0;

    private static int $sequence = 0;

    /** @var array<int, \WeakReference<Component>> components commit() visits, by creation order */
    private static array $commitQueue = [];

    private static bool $queueSorted = true;
    private static AppState $appState = AppState::Active;

    /** @var list<Component> Components currently inside render(). */
    private static array $renderStack = [];

    /** @var WeakMap<Component, ComponentLifecycleState>|null Components with child lists (their last real render). */
    private static ?WeakMap $children = null;

    /** @var list<array{depth: int, components: list<Component>}> */
    private static array $captures = [];

    /** @var WeakMap<Component, string>|null Tree keys of components mounted while hot reload restores state. */
    private static ?WeakMap $hotKeys = null;

    /** @var array<class-string, int> */
    private static array $hotRoots = [];

    private function __construct()
    {
    }

    public static function beginRender(): void
    {
        self::$pass++;
    }

    public static function retain(Component $component): void
    {
        $state = self::$states[$component] ?? null;
        if ($state !== null) {
            $state->seen = self::$pass;
        }
    }

    /** @param Closure(): Element $render */
    public static function render(Component $component, Closure $render): Element
    {
        self::enter($component);
        try {
            $element = $render();
            $component->rendered();

            return $element;
        } finally {
            array_pop(self::$renderStack);
        }
    }

    /**
     * Starts rendering a component: records it for this pass and as a child
     * of the component rendering it, then boots, sets up and mounts it on
     * first use. The caller must call leave() once it rendered.
     */
    public static function enter(Component $component): void
    {
        $states = self::$states ??= new WeakMap();
        $state = $states[$component] ?? null;
        $created = $state === null;
        if ($state === null) {
            $state = new ComponentLifecycleState();
            $state->sequence = ++self::$sequence;
            $states[$component] = $state;
        }
        if (!$state->queued && (!$state->attached || !$state->resumed)) {
            self::queue($component, $state);
        }

        $state->seen = self::$pass;
        $parent = self::$renderStack === [] ? null : self::$renderStack[count(self::$renderStack) - 1];
        if ($parent !== null && $parent !== $component) {
            self::addChild($parent, $component);
        }
        $hotKey = $created && ComponentStatePreserver::restoring()
            ? self::hotKey($parent === $component ? null : $parent, $component)
            : null;
        if (self::$captures !== []) {
            $capture = count(self::$captures) - 1;
            if (self::$captures[$capture]['depth'] === count(self::$renderStack)) {
                self::$captures[$capture]['components'][] = $component;
            }
        }
        self::$renderStack[] = $component;
        if ($state->mounted && $state->setup && $state->booted) {
            return;
        }
        try {
            if (!$state->booted) {
                $component->boot();
                $state->booted = true;
            }
            if (!$state->setup) {
                $component->__pamSetup();
                $state->setup = true;
            }
            if (!$state->mounted) {
                if ($hotKey !== null) {
                    // Like Fast Refresh: mount() and effects run again, on the preserved state.
                    ComponentStatePreserver::restore($component, $hotKey);
                }
                $component->mount();
                $state->mounted = true;
            }
        } catch (\Throwable $error) {
            $states[$component] = $state;
            array_pop(self::$renderStack);
            if (!$state->mounted) {
                // A mount() that threw never unmounts: release what it owned
                // (subscriptions, listeners, timers) now, or every retried
                // render of the error boundary stacked another copy.
                try {
                    $component->__pamDisposeResources();
                } catch (\Throwable) {
                }
            }

            throw $error;
        }
    }

    /**
     * Position of a component in the tree: `Class#n` segments from the root,
     * `n` counting same-class siblings in render order. The same code renders
     * the same keys, so state survives a hot reload; a structural edit above
     * a component changes its key and it starts fresh.
     */
    private static function hotKey(?Component $parent, Component $component): ?string
    {
        $class = $component::class;
        if ($parent === null) {
            $index = self::$hotRoots[$class] = (self::$hotRoots[$class] ?? -1) + 1;
            $key = $class.'#'.$index;
        } else {
            $parentKey = self::$hotKeys[$parent] ?? null;
            if ($parentKey === null) {
                return null;
            }
            $index = -1;
            foreach (self::$children[$parent]->children ?? [] as $sibling) {
                if ($sibling::class === $class) {
                    $index++;
                }
                if ($sibling === $component) {
                    break;
                }
            }
            $key = $parentKey.'/'.$class.'#'.max(0, $index);
        }
        $keys = self::$hotKeys ??= new WeakMap();
        $keys[$component] = $key;

        return $key;
    }

    /**
     * Hot reload: records the `#[State]` of every mounted component under its
     * tree key (see hotKey()), right before the request shuts down.
     */
    public static function checkpointHotReload(): int
    {
        $states = self::$states;
        if ($states === null) {
            return ComponentStatePreserver::checkpoint([]);
        }
        $children = new WeakMap();
        foreach (self::$children ?? [] as $holder) {
            foreach ($holder->children as $child) {
                $children[$child] = true;
            }
        }
        $roots = [];
        foreach ($states as $component => $state) {
            if ($state->mounted && !isset($children[$component])) {
                $roots[$state->sequence] = $component;
            }
        }
        ksort($roots);
        $keyed = [];
        $visited = new WeakMap();
        $rootIndexes = [];
        foreach ($roots as $root) {
            $index = $rootIndexes[$root::class] = ($rootIndexes[$root::class] ?? -1) + 1;
            self::collectHotKeys($root, $root::class.'#'.$index, $keyed, $visited, 0);
        }

        return ComponentStatePreserver::checkpoint($keyed);
    }

    /**
     * @param array<string, Component> $keyed
     * @param WeakMap<Component, true> $visited
     */
    private static function collectHotKeys(
        Component $component,
        string $key,
        array &$keyed,
        WeakMap $visited,
        int $depth,
    ): void {
        if ($depth > 256 || isset($visited[$component])) {
            return;
        }
        $visited[$component] = true;
        $state = self::$states[$component] ?? null;
        if ($state === null || !$state->mounted) {
            return;
        }
        $keyed[$key] = $component;
        $indexes = [];
        foreach (self::$children[$component]->children ?? [] as $child) {
            $index = $indexes[$child::class] = ($indexes[$child::class] ?? -1) + 1;
            self::collectHotKeys($child, $key.'/'.$child::class.'#'.$index, $keyed, $visited, $depth + 1);
        }
    }

    public static function leave(): void
    {
        array_pop(self::$renderStack);
    }

    /**
     * Runs a render and returns its result with the top-level components it
     * rendered, so a caller can later reuse the result without rendering
     * (see adopt()).
     *
     * @template T
     * @param Closure(): T $render
     * @return array{0: T, 1: list<Component>}
     */
    public static function capture(Closure $render): array
    {
        self::$captures[] = ['depth' => count(self::$renderStack), 'components' => []];
        try {
            $result = $render();
        } catch (\Throwable $error) {
            array_pop(self::$captures);
            throw $error;
        }
        $capture = array_pop(self::$captures);

        return [$result, $capture['components']];
    }

    /**
     * Keeps components of a reused (frozen) render result mounted and records
     * them as children of the component rendering now.
     *
     * @param list<Component> $components
     */
    public static function adopt(array $components): void
    {
        $parent = self::$renderStack === [] ? null : self::$renderStack[count(self::$renderStack) - 1];
        foreach ($components as $component) {
            self::retain($component);
            PamPhpRegistry::retainScope($component);
            self::retainSubtree($component);
            if ($parent !== null && $parent !== $component) {
                self::addChild($parent, $component);
            }
            if (self::$captures !== []) {
                $capture = count(self::$captures) - 1;
                if (self::$captures[$capture]['depth'] === count(self::$renderStack)) {
                    self::$captures[$capture]['components'][] = $component;
                }
            }
        }
    }

    /** Called when a component really renders (not reused), before its children render. */
    public static function beginComponentRender(Component $component): void
    {
        $children = self::$children ??= new WeakMap();
        $holder = $children[$component] ?? null;
        if ($holder === null) {
            $children[$component] = $holder = new ComponentLifecycleState();
        }
        $holder->children = [];
        $holder->walked = -1;
        $holder->retained = null;
        $state = self::$states[$component] ?? null;
        if ($state !== null) {
            $state->rendered = self::$pass;
        }
    }

    /**
     * True when the component is part of this pass without having rendered
     * (it, or an ancestor, was reused): what it rendered last stays.
     */
    public static function retainedWithoutRender(Component $component): bool
    {
        $state = self::$states[$component] ?? null;

        return $state !== null && $state->seen === self::$pass && $state->rendered !== self::$pass;
    }

    private static function addChild(Component $parent, Component $component): void
    {
        $children = self::$children ??= new WeakMap();
        $holder = $children[$parent] ?? null;
        if ($holder === null) {
            $children[$parent] = $holder = new ComponentLifecycleState();
        }
        $holder->children[] = $component;
    }

    /**
     * Keeps every component rendered under a memoized (reused) component
     * mounted for this pass, recursively.
     */
    public static function retainSubtree(Component $component, int $depth = 0): void
    {
        $holder = self::$children[$component] ?? null;
        if ($holder === null || $depth > 256 || $holder->walked === self::$pass) {
            return;
        }
        $holder->walked = self::$pass;
        // Their compiled instances stay with them: the registry keeps the
        // instances of owners retained without rendering.
        $pass = self::$pass;
        foreach ($holder->retained ??= self::descendantStates($holder, $depth) as $state) {
            $state->seen = $pass;
        }
    }

    /** @return list<ComponentLifecycleState> */
    private static function descendantStates(ComponentLifecycleState $holder, int $depth): array
    {
        $states = [];
        foreach ($holder->children as $child) {
            $state = self::$states[$child] ?? null;
            if ($state !== null) {
                $states[] = $state;
            }
            $childHolder = self::$children[$child] ?? null;
            if ($childHolder !== null && $depth + 1 <= 256) {
                array_push($states, ...self::descendantStates($childHolder, $depth + 1));
            }
        }

        return $states;
    }

    /**
     * Marks mounted components whose own state changed outside the tracked
     * paths (direct property writes from callbacks) dirty, with ancestors,
     * so memoized parents re-render down to them.
     */
    public static function detectChanges(): void
    {
        $states = self::$states;
        if ($states === null) {
            return;
        }
        foreach ($states as $component => $state) {
            // Marking an already dirty component is a no-op: skip its snapshot.
            if ($state->mounted && !DependencyTracker::isDirty($component) && $component->__pamStateChanged()) {
                DependencyTracker::markDirty($component);
            }
        }
    }

    public static function finishRender(): void
    {
        $states = self::$states;

        if ($states === null) {
            return;
        }

        foreach ($states as $component => $state) {
            if (!$state->mounted || $state->seen === self::$pass) {
                continue;
            }
            if ($state->resumed) {
                $component->paused();
            }
            try {
                $component->unmount();
            } finally {
                $component->__pamDisposeResources();
            }
            $state->mounted = false;
            $state->attached = false;
            $state->resumed = false;
            $states[$component] = $state;
        }
    }

    /**
     * Attaches, resumes and runs the effects of the components this pass
     * rendered, in creation order. Only components that still need one of
     * those (new, remounted or paused ones, and those with effects) are
     * queued, so a frame does not visit every mounted component.
     */
    public static function commit(): void
    {
        $states = self::$states;

        if ($states === null) {
            return;
        }

        $visited = [];
        do {
            if (!self::$queueSorted) {
                ksort(self::$commitQueue);
                self::$queueSorted = true;
            }
            $more = false;
            foreach (self::$commitQueue as $sequence => $reference) {
                if (isset($visited[$sequence])) {
                    continue;
                }
                $visited[$sequence] = true;
                $component = $reference->get();
                $state = $component === null ? null : ($states[$component] ?? null);
                if ($state === null || $state->sequence !== $sequence) {
                    unset(self::$commitQueue[$sequence]);
                    continue;
                }
                if (!$state->mounted || $state->seen !== self::$pass) {
                    continue;
                }
                if (!$state->attached) {
                    $component->attached();
                    $state->attached = true;
                }
                if (self::$appState !== AppState::Background && !$state->resumed) {
                    $component->__pamResumeResources();
                    $component->resumed();
                    $state->resumed = true;
                }
                $component->__pamRunEffects();
                if ($state->resumed && !Component::__pamHasEffects($component)) {
                    unset(self::$commitQueue[$sequence]);
                    $state->queued = false;
                }
            }
            // Components entered by the hooks above join this commit, as
            // they would have been visited by a live iteration.
            foreach (self::$commitQueue as $sequence => $_) {
                if (!isset($visited[$sequence])) {
                    $more = true;
                    break;
                }
            }
        } while ($more);
    }

    private static function queue(Component $component, ComponentLifecycleState $state): void
    {
        $state->queued = true;
        $last = array_key_last(self::$commitQueue);
        if ($last !== null && $last > $state->sequence) {
            self::$queueSorted = false;
        }
        self::$commitQueue[$state->sequence] = \WeakReference::create($component);
    }

    /**
     * `paused()`/`resumed()` follow the foreground: only Background pauses a
     * component. Inactive means transient system UI (permission prompt, picker,
     * share sheet, biometric dialog) is drawn over the still-visible app, so
     * components stay resumed and observe it through `inactive()`/`activated()`.
     */
    /** Foreground state last delivered to components. */
    public static function currentAppState(): AppState
    {
        return self::$appState;
    }

    public static function appState(AppState $appState): void
    {
        $previous = self::$appState;

        if ($appState === $previous) {
            return;
        }
        self::$appState = $appState;
        $states = self::$states;

        if ($states === null) {
            return;
        }

        foreach ($states as $component => $state) {
            if (!$state->mounted || !$state->attached) {
                continue;
            }
            if ($appState === AppState::Background) {
                if ($state->resumed) {
                    $component->__pamSuspendResources();
                    $component->paused();
                    $state->resumed = false;
                    $state->inactive = false;
                }
            } elseif (!$state->resumed) {
                $component->__pamResumeResources();
                $component->resumed();
                $state->resumed = true;
                $state->inactive = false;
            } elseif ($appState === AppState::Inactive) {
                $component->inactive();
                $state->inactive = true;
            } elseif ($state->inactive) {
                $component->activated();
                $state->inactive = false;
            }
            $states[$component] = $state;
        }
    }

    public static function forget(Component $component): void
    {
        $states = self::$states;
        $state = $states[$component] ?? null;

        if ($state === null) {
            return;
        }
        if ($state->resumed) {
            $component->paused();
        }
        try {
            if ($state->mounted) {
                $component->unmount();
            }
        } finally {
            $component->__pamDisposeResources();
            $component->__pamCleanup();
            DependencyTracker::forget($component);
        }
        unset($states[$component]);
    }

    public static function shutdown(): void
    {
        $states = self::$states;

        if ($states !== null) {
            foreach ($states as $component => $state) {
                if ($state->resumed) {
                    $component->paused();
                }
                try {
                    if ($state->mounted) {
                        $component->unmount();
                    }
                } finally {
                    $component->__pamDisposeResources();
                    $component->__pamCleanup();
                    DependencyTracker::forget($component);
                    $component->__pamRelease();
                }
            }
        }

        self::$states = null;
        self::$children = null;
        self::$hotKeys = null;
        self::$hotRoots = [];
        ComponentStatePreserver::reset();
        self::$commitQueue = [];
        self::$queueSorted = true;
        self::$renderStack = [];
        self::$captures = [];
        self::$pass = 0;
        self::$appState = AppState::Active;
        DependencyTracker::reset();
    }
}
