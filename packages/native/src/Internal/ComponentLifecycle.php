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
    private static bool $active = true;

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
            'seen' => 0,
        ];

        $state['seen'] = self::$pass;
        $states[$component] = $state;
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
            if (self::$active && !$state['resumed']) {
                $component->resumed();
                $state['resumed'] = true;
            }
            $component->__pamRunEffects();
            $states[$component] = $state;
        }
    }

    public static function appState(AppState $appState): void
    {
        $active = $appState === AppState::Active;

        if ($active === self::$active) {
            return;
        }
        self::$active = $active;
        $states = self::$states;

        if ($states === null) {
            return;
        }

        foreach ($states as $component => $state) {
            if (!$state['mounted'] || !$state['attached']) {
                continue;
            }
            if ($active && !$state['resumed']) {
                $component->resumed();
                $state['resumed'] = true;
            } elseif (!$active && $state['resumed']) {
                $component->paused();
                $state['resumed'] = false;
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
        self::$pass = 0;
        self::$active = true;
        DependencyTracker::reset();
    }
}
