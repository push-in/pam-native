<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Component;
use WeakMap;

final class DependencyTracker
{
    /** @var list<Component> */
    private static array $stack = [];
    /** @var WeakMap<object, array<string, WeakMap<Component, true>>>|null */
    private static ?WeakMap $subscribers = null;
    /** @var WeakMap<Component, list<array{object, string}>>|null */
    private static ?WeakMap $dependencies = null;
    /** @var WeakMap<Component, true>|null Components whose own render inputs changed. */
    private static ?WeakMap $dirty = null;
    /**
     * Direct (tracked) children that are dirty or have dirty descendants, by
     * parent: a parent dirty only through them can keep its own render and
     * update just the paths to them (see Internal\GranularRender).
     *
     * @var WeakMap<Component, WeakMap<Component, true>>|null
     */
    private static ?WeakMap $pending = null;
    /** @var WeakMap<Component, Component>|null */
    private static ?WeakMap $parents = null;
    private static int $epoch = 0;
    /** Bumped whenever a component newly becomes dirty (pure event dispatch compares it). */
    private static int $revision = 0;
    private static bool $memoization = true;

    private function __construct()
    {
    }

    public static function begin(Component $component): void
    {
        self::clear($component);
        $last = array_key_last(self::$stack);
        $parent = $last === null ? null : self::$stack[$last];
        $parents = self::$parents ??= new WeakMap();
        if ($parent instanceof Component && $parent !== $component) {
            $parents[$component] = $parent;
        } else {
            unset($parents[$component]);
        }
        self::$stack[] = $component;
        if (PortalRegistry::active()) {
            PortalRegistry::instance()->beginOwner($component);
        }
    }

    /** The component currently rendering, if any. */
    public static function current(): ?Component
    {
        $last = array_key_last(self::$stack);
        $component = $last === null ? null : self::$stack[$last];

        return $component instanceof Component ? $component : null;
    }

    public static function end(Component $component): void
    {
        $current = array_pop(self::$stack);
        if ($current !== $component) {
            self::$stack = [];
            throw new \LogicException('Component dependency tracking stack is corrupted.');
        }
        $dirty = self::$dirty ??= new WeakMap();
        unset($dirty[$component]);
        if (self::$pending !== null) {
            unset(self::$pending[$component]);
        }
        if (PortalRegistry::active()) {
            PortalRegistry::instance()->endOwner($component);
        }
    }

    /** Tracks reads for $component again, keeping what it already read (lazy list rows). */
    public static function resume(Component $component): void
    {
        self::$stack[] = $component;
    }

    public static function suspend(Component $component): void
    {
        $current = array_pop(self::$stack);
        if ($current !== $component) {
            self::$stack = [];
            throw new \LogicException('Component dependency tracking stack is corrupted.');
        }
    }

    public static function read(object $source, string $key): void
    {
        $last = array_key_last(self::$stack);
        $component = $last === null ? null : self::$stack[$last];
        if (!$component instanceof Component) {
            return;
        }
        $subscribers = self::$subscribers ??= new WeakMap();
        $keys = $subscribers[$source] ?? [];
        $components = $keys[$key] ?? new WeakMap();
        $components[$component] = true;
        $keys[$key] = $components;
        $subscribers[$source] = $keys;
        $dependencies = self::$dependencies ??= new WeakMap();
        $values = $dependencies[$component] ?? [];
        $values[] = [$source, $key];
        $dependencies[$component] = $values;
    }

    public static function invalidate(object $source, string $key): void
    {
        $map = self::$subscribers;
        $keys = $map !== null && isset($map[$source]) ? $map[$source] : [];
        $dirty = self::$dirty ??= new WeakMap();
        foreach ([$keys[$key] ?? null, $keys['*'] ?? null] as $components) {
            if (!$components instanceof WeakMap) {
                continue;
            }
            foreach ($components as $component => $_) {
                self::markComponentAndAncestorsDirty($component, $dirty);
            }
        }
    }

    /**
     * True when no tracked dependency, event handler or explicit invalidation
     * marked the component (or a descendant) dirty since it last rendered.
     * Callers combine this with the component's own state snapshot and epoch.
     */
    public static function canSkip(Component $component): bool
    {
        return self::ownClean($component) && !self::hasPending($component);
    }

    /**
     * True when nothing marked the component itself dirty since it last
     * rendered (its descendants may be).
     */
    public static function ownClean(Component $component): bool
    {
        return self::$memoization && (self::$dirty === null || !isset(self::$dirty[$component]));
    }

    /** True when a descendant of the component is dirty. */
    public static function hasPending(Component $component): bool
    {
        $pending = self::$pending;

        return $pending !== null && isset($pending[$component]) && count($pending[$component]) > 0;
    }

    /**
     * Direct (tracked) children that are dirty or lead to dirty descendants.
     *
     * @return list<Component>
     */
    public static function pendingChildren(Component $component): array
    {
        $pending = self::$pending;
        if ($pending === null || !isset($pending[$component])) {
            return [];
        }
        $children = [];
        foreach ($pending[$component] as $child => $_) {
            $children[] = $child;
        }

        return $children;
    }

    /**
     * After a granular update of $component: keeps only the children still
     * dirty (or with dirty descendants), e.g. one marked again while it
     * rendered, so its ancestors keep leading to it.
     */
    public static function settle(Component $component): void
    {
        $pending = self::$pending;
        if ($pending === null || !isset($pending[$component])) {
            return;
        }
        $children = $pending[$component];
        foreach (self::pendingChildren($component) as $child) {
            if (!self::isDirty($child) && !self::hasPending($child)) {
                unset($children[$child]);
            }
        }
        if (count($children) === 0) {
            unset($pending[$component]);
        }
    }

    /** Forgets the component's dirty descendants (what it rendered was replaced). */
    public static function clearPending(Component $component): void
    {
        if (self::$pending !== null) {
            unset(self::$pending[$component]);
        }
    }

    /** True when the component itself is marked dirty. */
    public static function isDirty(Component $component): bool
    {
        return self::$dirty !== null && isset(self::$dirty[$component]);
    }

    /** Monotonic counter bumped each time a component newly becomes dirty. */
    public static function revision(): int
    {
        return self::$revision;
    }

    /** Monotonic counter bumped by every global invalidation. */
    public static function epoch(): int
    {
        return self::$epoch;
    }

    public static function memoization(?bool $enabled = null): bool
    {
        if ($enabled !== null) {
            self::$memoization = $enabled;
            self::$epoch++;
        }

        return self::$memoization;
    }

    public static function markDirty(Component $component): void
    {
        $dirty = self::$dirty ??= new WeakMap();
        self::markComponentAndAncestorsDirty($component, $dirty);
    }

    public static function invalidateAll(): void
    {
        self::$epoch++;
    }

    public static function forget(Component $component): void
    {
        if (PortalRegistry::active()) {
            PortalRegistry::instance()->forgetOwner($component);
        }
        self::clear($component);
        $dirty = self::$dirty ??= new WeakMap();
        unset($dirty[$component]);
        $parents = self::$parents ??= new WeakMap();
        $pending = self::$pending;
        if ($pending !== null) {
            unset($pending[$component]);
            $parent = $parents[$component] ?? null;
            $siblings = $parent !== null ? ($pending[$parent] ?? null) : null;
            if ($siblings !== null) {
                unset($siblings[$component]);
            }
        }
        unset($parents[$component]);
    }

    public static function reset(): void
    {
        self::$stack = [];
        self::$subscribers = null;
        self::$dependencies = null;
        self::$dirty = null;
        self::$pending = null;
        self::$parents = null;
        self::$epoch++;
    }

    /**
     * Marks the component dirty and records the path to it in its ancestors
     * (each one lists the child leading to it), up to the first ancestor that
     * already lists it.
     *
     * @param WeakMap<Component, true> $dirty
     */
    private static function markComponentAndAncestorsDirty(
        Component $component,
        WeakMap $dirty,
    ): void {
        if (isset($dirty[$component])) {
            return;
        }
        $dirty[$component] = true;
        self::$revision++;
        $parents = self::$parents;
        if ($parents === null) {
            return;
        }
        $pending = self::$pending ??= new WeakMap();
        $child = $component;
        while (isset($parents[$child])) {
            $parent = $parents[$child];
            if ($parent === $child) {
                return;
            }
            $children = $pending[$parent] ?? null;
            if ($children === null) {
                $children = new WeakMap();
                $pending[$parent] = $children;
            } elseif (isset($children[$child])) {
                return;
            }
            $children[$child] = true;
            $child = $parent;
        }
    }

    private static function clear(Component $component): void
    {
        $dependencies = self::$dependencies;
        if ($dependencies === null || !isset($dependencies[$component])) {
            return;
        }
        foreach ($dependencies[$component] as [$source, $key]) {
            $map = self::$subscribers;
            $keys = $map !== null && isset($map[$source]) ? $map[$source] : [];
            $components = $keys[$key] ?? null;
            if ($components instanceof WeakMap) {
                unset($components[$component]);
            }
        }
        unset($dependencies[$component]);
    }
}
