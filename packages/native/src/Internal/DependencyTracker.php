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
    /** @var WeakMap<Component, true>|null */
    private static ?WeakMap $dirty = null;

    private function __construct()
    {
    }

    public static function begin(Component $component): void
    {
        self::clear($component);
        self::$stack[] = $component;
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
    }

    public static function read(object $source, string $key): void
    {
        $component = self::$stack[array_key_last(self::$stack)] ?? null;
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
                $dirty[$component] = true;
            }
        }
    }

    public static function canSkip(Component $component): bool
    {
        $dependencies = self::$dependencies;
        if ($dependencies === null || !isset($dependencies[$component])) {
            return false;
        }

        return !isset((self::$dirty ??= new WeakMap())[$component]);
    }

    public static function markDirty(Component $component): void
    {
        $dirty = self::$dirty ??= new WeakMap();
        $dirty[$component] = true;
    }

    public static function forget(Component $component): void
    {
        self::clear($component);
        $dirty = self::$dirty ??= new WeakMap();
        unset($dirty[$component]);
    }

    public static function reset(): void
    {
        self::$stack = [];
        self::$subscribers = null;
        self::$dependencies = null;
        self::$dirty = null;
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
