<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use Pam\Native\AppState;
use Pam\Native\Component;
use Pam\Native\Element;
use WeakMap;

final class ComponentLifecycle
{
    /**
     * @var WeakMap<Component, array{
     *     booted: bool,
     *     setup: bool,
     *     mounted: bool,
     *     attached: bool,
     *     resumed: bool,
     *     seen: int
     * }>|null
     */
    private static ?WeakMap $states = null;

    private static int $pass = 0;
    private static AppState $appState = AppState::Active;

    /** @var list<Component> Components currently inside render(). */
    private static array $renderStack = [];

    /** @var WeakMap<Component, list<Component>>|null Child components rendered during each component's last real render. */
    private static ?WeakMap $children = null;

    /** @var list<array{depth: int, components: list<Component>}> */
    private static array $captures = [];

    private function __construct()
    {
    }

    public static function beginRender(): void
    {
        self::$pass++;
    }

    public static function retain(Component $component): void
    {
        $states = self::$states;
        $state = $states[$component] ?? null;
        if ($state === null) {
            return;
        }
        $state['seen'] = self::$pass;
        $states[$component] = $state;
    }

    /** @param Closure(): Element $render */
    public static function render(Component $component, Closure $render): Element
    {
        $states = self::$states ??= new WeakMap();
        $state = $states[$component] ?? [
            'booted' => false,
            'setup' => false,
            'mounted' => false,
            'attached' => false,
            'resumed' => false,
            'inactive' => false,
            'seen' => 0,
        ];

        $state['seen'] = self::$pass;
        $states[$component] = $state;
        $parent = self::$renderStack === [] ? null : self::$renderStack[count(self::$renderStack) - 1];
        if ($parent !== null && $parent !== $component) {
            $children = self::$children ??= new WeakMap();
            $list = $children[$parent] ?? [];
            $list[] = $component;
            $children[$parent] = $list;
        }
        if (self::$captures !== []) {
            $capture = count(self::$captures) - 1;
            if (self::$captures[$capture]['depth'] === count(self::$renderStack)) {
                self::$captures[$capture]['components'][] = $component;
            }
        }
        self::$renderStack[] = $component;
        try {
            if (!$state['booted']) {
                $component->boot();
                $state['booted'] = true;
            }
            if (!$state['setup']) {
                $component->__pamSetup();
                $state['setup'] = true;
            }
            if (!$state['mounted']) {
                $component->mount();
                $state['mounted'] = true;
            }
            $states[$component] = $state;

            $element = $render();
            $component->rendered();

            return $element;
        } catch (\Throwable $error) {
            $states[$component] = $state;
            throw $error;
        } finally {
            array_pop(self::$renderStack);
        }
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
                $children = self::$children ??= new WeakMap();
                $list = $children[$parent] ?? [];
                $list[] = $component;
                $children[$parent] = $list;
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
        $children[$component] = [];
    }

    /**
     * Keeps every component rendered under a memoized (reused) component
     * mounted for this pass, recursively.
     */
    public static function retainSubtree(Component $component, int $depth = 0): void
    {
        $children = self::$children;
        if ($children === null || $depth > 256 || !isset($children[$component])) {
            return;
        }
        foreach ($children[$component] as $child) {
            self::retain($child);
            PamPhpRegistry::retainScope($child);
            self::retainSubtree($child, $depth + 1);
        }
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
            if ($state['mounted'] && $component->__pamStateChanged()) {
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
            if (!$state['mounted'] || $state['seen'] === self::$pass) {
                continue;
            }
            if ($state['resumed']) {
                $component->paused();
            }
            $component->unmount();
            $state['mounted'] = false;
            $state['attached'] = false;
            $state['resumed'] = false;
            $states[$component] = $state;
        }
    }

    public static function commit(): void
    {
        $states = self::$states;

        if ($states === null) {
            return;
        }

        foreach ($states as $component => $state) {
            if (!$state['mounted'] || $state['seen'] !== self::$pass) {
                continue;
            }
            if (!$state['attached']) {
                $component->attached();
                $state['attached'] = true;
            }
            if (self::$appState !== AppState::Background && !$state['resumed']) {
                $component->resumed();
                $state['resumed'] = true;
            }
            $component->__pamRunEffects();
            $states[$component] = $state;
        }
    }

    /**
     * `paused()`/`resumed()` follow the foreground: only Background pauses a
     * component. Inactive means transient system UI (permission prompt, picker,
     * share sheet, biometric dialog) is drawn over the still-visible app, so
     * components stay resumed and observe it through `inactive()`/`activated()`.
     */
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
            if (!$state['mounted'] || !$state['attached']) {
                continue;
            }
            if ($appState === AppState::Background) {
                if ($state['resumed']) {
                    $component->paused();
                    $state['resumed'] = false;
                    $state['inactive'] = false;
                }
            } elseif (!$state['resumed']) {
                $component->resumed();
                $state['resumed'] = true;
                $state['inactive'] = false;
            } elseif ($appState === AppState::Inactive) {
                $component->inactive();
                $state['inactive'] = true;
            } elseif ($state['inactive'] ?? false) {
                $component->activated();
                $state['inactive'] = false;
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
        if ($state['resumed']) {
            $component->paused();
        }
        try {
            if ($state['mounted']) {
                $component->unmount();
            }
        } finally {
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
                if ($state['resumed']) {
                    $component->paused();
                }
                try {
                    if ($state['mounted']) {
                        $component->unmount();
                    }
                } finally {
                    $component->__pamCleanup();
                    DependencyTracker::forget($component);
                }
            }
        }

        self::$states = null;
        self::$children = null;
        self::$renderStack = [];
        self::$captures = [];
        self::$pass = 0;
        self::$appState = AppState::Active;
        DependencyTracker::reset();
    }
}
