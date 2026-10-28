<?php

declare(strict_types=1);

/*
 * Architecture v2 functions, autoloaded by Composer ("files"):
 *
 *     use function Pam\Native\{signal, computed, watch, onMounted, useColorScheme, useResource};
 *
 * Reactivity (signal, computed, watch), lifecycle hooks for setup()
 * (onMounted, onUnmounted, onFocus, onBlur), effectScope() and the framework
 * composables. Composables called inside setup() release their platform
 * subscription when the component unmounts; called outside any scope (for
 * example in index.php for App::globals()) they live as long as the app.
 */

namespace Pam\Native;

use Closure;
use Pam\Native\Composables\AppStateRef;
use Pam\Native\Composables\ColorScheme;
use Pam\Native\Composables\FontScale;
use Pam\Native\Composables\KeyboardState;
use Pam\Native\Composables\LocaleState;
use Pam\Native\Composables\NetworkState;
use Pam\Native\Composables\Resource;
use Pam\Native\Composables\SafeAreaInsets;
use Pam\Native\Composables\TimezoneState;
use Pam\Native\Composables\Sources;
use Pam\Native\Composables\WindowDimensions;
use Pam\Native\Signals\ComputedSignal;
use Pam\Native\Signals\EffectScope;
use Pam\Native\Signals\ReadableSignal;
use Pam\Native\Signals\Signal;
use Pam\Native\Signals\Watcher;

if (!\function_exists('Pam\Native\signal')) {
    /**
     * A reactive value: `$count = signal(0); $count->value++;` and
     * `{{ $count.value }}` in a template.
     *
     * @template T
     * @param T $value
     * @return Signal<T>
     */
    function signal(mixed $value = null): Signal
    {
        return new Signal($value);
    }
}

if (!\function_exists('Pam\Native\computed')) {
    /**
     * A cached value derived from signals, recomputed only after one of them changed.
     *
     * @template T
     * @param Closure(): T $compute
     * @return ComputedSignal<T>
     */
    function computed(Closure $compute): ComputedSignal
    {
        return new ComputedSignal($compute);
    }
}

if (!\function_exists('Pam\Native\watch')) {
    /**
     * Calls `$callback($next, $previous)` when $source changes. The watcher
     * stops with the setup() scope that created it, or when disposed.
     *
     * @param ReadableSignal<mixed>|Closure(): mixed $source
     * @param Closure(mixed, mixed): void $callback
     */
    function watch(ReadableSignal|Closure $source, Closure $callback, bool $immediate = false): Watcher
    {
        $watcher = new Watcher($source, $callback, $immediate);
        EffectScope::current()?->own($watcher);

        return $watcher;
    }
}

if (!\function_exists('Pam\Native\effectScope')) {
    /**
     * Runs $setup in a new scope and returns it; dispose() it to release
     * every watcher and composable created inside (tests, services).
     *
     * @param Closure(): mixed $setup
     */
    function effectScope(Closure $setup): EffectScope
    {
        $scope = new EffectScope();
        EffectScope::current()?->own($scope);
        $scope->run($setup);

        return $scope;
    }
}

if (!\function_exists('Pam\Native\onMounted')) {
    /** Runs $hook once the component is attached (after its first native commit). */
    function onMounted(Closure $hook): void
    {
        EffectScope::require('onMounted')->onMounted($hook);
    }
}

if (!\function_exists('Pam\Native\onUnmounted')) {
    /** Runs $hook when the component unmounts, before its subscriptions are released. */
    function onUnmounted(Closure $hook): void
    {
        EffectScope::require('onUnmounted')->onUnmounted($hook);
    }
}

if (!\function_exists('Pam\Native\onFocus')) {
    /**
     * Runs $hook each time the screen becomes visible (navigation focus).
     * Components outside a routed screen count as focused while mounted.
     */
    function onFocus(Closure $hook): void
    {
        EffectScope::require('onFocus')->onFocus($hook);
    }
}

if (!\function_exists('Pam\Native\onBlur')) {
    /** Runs $hook each time the screen is covered or left, and on unmount while focused. */
    function onBlur(Closure $hook): void
    {
        EffectScope::require('onBlur')->onBlur($hook);
    }
}

if (!\function_exists('Pam\Native\useColorScheme')) {
    /** `->value` ('light'|'dark'), `->mode` ('system'|'light'|'dark'), `->set('dark')`. */
    function useColorScheme(): ColorScheme
    {
        $source = Sources::appearance();
        $source->acquireForScope();

        return new ColorScheme($source);
    }
}

if (!\function_exists('Pam\Native\useSafeAreaInsets')) {
    /** `->top`, `->right`, `->bottom`, `->left` in points. */
    function useSafeAreaInsets(): SafeAreaInsets
    {
        $source = Sources::window();
        $source->acquireForScope();

        return new SafeAreaInsets($source);
    }
}

if (!\function_exists('Pam\Native\useWindowDimensions')) {
    /** `->width`, `->height`, `->scale`, `->fontScale`, `->landscape`, `->deviceClass`. */
    function useWindowDimensions(): WindowDimensions
    {
        $source = Sources::window();
        $source->acquireForScope();

        return new WindowDimensions($source);
    }
}

if (!\function_exists('Pam\Native\useFontScale')) {
    /** `->value` (system scale), `->effective`/`->display` (after TextScalePolicy), `->for($size)`. */
    function useFontScale(): FontScale
    {
        $source = Sources::window();
        $source->acquireForScope();

        return new FontScale($source);
    }
}

if (!\function_exists('Pam\Native\useKeyboard')) {
    /** `->visible`, `->height`, `->durationMs`, `->dismiss()`. */
    function useKeyboard(): KeyboardState
    {
        $source = Sources::keyboard();
        $source->acquireForScope();

        return new KeyboardState($source);
    }
}

if (!\function_exists('Pam\Native\useAppState')) {
    /** `->value` (AppState), `->active`, `->background`. */
    function useAppState(): AppStateRef
    {
        $source = Sources::appState();
        $source->acquireForScope();

        return new AppStateRef($source);
    }
}

if (!\function_exists('Pam\Native\useNetwork')) {
    /** `->online` (also `->value`), `->known`, `->type`, `->expensive`. */
    function useNetwork(): NetworkState
    {
        $source = Sources::network();
        $source->acquireForScope();

        return new NetworkState($source);
    }
}

if (!\function_exists('Pam\Native\useLocale')) {
    /** `->value` ("pt-BR"), `->language`, `->region`, `->device`, `->known`, `->overridden`, `->set(?string)`. */
    function useLocale(): LocaleState
    {
        $device = Sources::device();
        $device->acquireForScope();
        $overrides = Sources::i18n();
        $overrides->acquireForScope();

        return new LocaleState($device, $overrides);
    }
}

if (!\function_exists('Pam\Native\useTimezone')) {
    /** `->value` ("America/Sao_Paulo"), `->device`, `->known`, `->overridden`, `->zone()`, `->now()`, `->set(?string)`. */
    function useTimezone(): TimezoneState
    {
        $device = Sources::device();
        $device->acquireForScope();
        $overrides = Sources::i18n();
        $overrides->acquireForScope();

        return new TimezoneState($device, $overrides);
    }
}

if (!\function_exists('Pam\Native\useResource')) {
    /**
     * Data loaded off the event path, with `->data`, `->loading`, `->error`,
     * `->reload()`, `->more()` and optimistic `->prepend()`, `->append()`,
     * `->replace($tmp, $real)`, `->markFailed($tmp)`, `->remove($item)`.
     *
     * @param Closure(mixed...): mixed $loader `fn (?string $cursor)` when paginated, else `fn ()`
     * @param bool $lazy start on first read instead of immediately
     * @param string|null $cache show the last result for this key at once, refresh behind it
     * @param string $key item field identifying list items for the optimistic helpers
     */
    function useResource(
        Closure $loader,
        bool $paginate = false,
        bool $refreshOnFocus = false,
        bool $lazy = false,
        ?string $cache = null,
        string $key = 'id',
        ?Closure $nextCursor = null,
        ?Closure $items = null,
    ): Resource {
        return new Resource($loader, $paginate, $refreshOnFocus, $lazy, $cache, $key, $nextCursor, $items);
    }
}
