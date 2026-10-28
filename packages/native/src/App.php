<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use Pam\Native\Diagnostics\ErrorReporter;
use Pam\Native\Diagnostics\RuntimeError;
use Pam\Native\Diagnostics\RuntimeErrorPhase;
use Pam\Native\Internal\PamPhpRegistry;
use Pam\Native\Internal\Runtime;
use Pam\Native\Dom\Document;
use Pam\Native\Plugin\PluginManager;
use Pam\Native\Navigation\TabNavigator;
use Throwable;

final class App
{
    private static ?Theme $activeTheme = null;

    private static ?Closure $appStateHandler = null;

    /** @var array<int, Closure(AppState): void> */
    private static array $stateListeners = [];

    private static int $nextStateListener = 1;

    private static ?AppState $currentState = null;

    /** True once components were registered explicitly or discovered by convention. */
    private static bool $componentsConfigured = false;

    private function __construct()
    {
    }

    public static function run(Renderable|Closure $root): void
    {
        try {
            self::discoverConventionalComponents();
            PluginManager::boot();
            if ($root instanceof TabNavigator) {
                Runtime::onDimensions($root->dimensions(...));
            }
            Runtime::boot($root);
        } catch (Throwable $error) {
            Runtime::reportError($error, RuntimeErrorPhase::Boot);

            throw $error;
        }
    }

    /**
     * Registers application-wide values, visible as `$name` in every template
     * and as `$this->name` in every component. Adds to what is already
     * registered; a repeated key overwrites. Values may be anything: a signal,
     * a composable (`useColorScheme()`), an object, an array, a scalar or a
     * closure. Reactive values re-render whoever reads them.
     *
     * ```php
     * App::globals(['scheme' => useColorScheme(), 'insets' => useSafeAreaInsets()]);
     * ```
     *
     * @param array<string, mixed> $values
     */
    public static function globals(array $values): void
    {
        Globals::merge($values);
    }

    /**
     * Registers one global. Registering a name twice without `replace: true`
     * is an error in development builds.
     */
    public static function global(string $name, mixed $value, bool $replace = false): void
    {
        Globals::set($name, $value, $replace);
    }

    /** Removes a global; readers re-render without it. */
    public static function forget(string $name): void
    {
        Globals::forget($name);
    }

    /**
     * Creates a retained, queryable visual document that can be passed directly
     * to App::run(). The document remains the mutation handle for its lifetime.
     */
    public static function document(Renderable $root): Document
    {
        return Document::from($root);
    }

    public static function views(string $path, ?string $cachePath = null): void
    {
        View::configure($path, $cachePath);
    }

    public static function components(
        string $path,
        ?string $cachePath = null,
    ): void {
        self::$componentsConfigured = true;
        try {
            PamPhpRegistry::discover(
                $path,
                $cachePath ?? getcwd().'/.pam/components',
            );
        } catch (Throwable $error) {
            Runtime::reportError($error, RuntimeErrorPhase::Boot);

            throw $error;
        }
    }

    /**
     * Architecture v2 convention: without an App::components() call, App::run()
     * registers the components under `<project root>/src` with the compiled
     * cache in `<project root>/.pam-native/components`. Call App::components()
     * before App::run() to use other paths.
     *
     * @internal
     */
    public static function discoverConventionalComponents(?string $projectRoot = null): bool
    {
        if (self::$componentsConfigured) {
            return false;
        }
        $root = $projectRoot ?? PluginManager::projectRoot();
        if ($root === null) {
            $script = $_SERVER['SCRIPT_FILENAME'] ?? null;
            $root = is_string($script) && $script !== '' ? dirname($script) : null;
        }
        if ($root === null || !is_dir($root.'/src')) {
            return false;
        }
        self::components($root.'/src', $root.'/.pam-native/components');

        return true;
    }

    /** @internal Test hook: forget whether components were configured. */
    public static function resetComponentDiscovery(): void
    {
        self::$componentsConfigured = false;
    }

    /** @param array<string, mixed> $props */
    public static function make(string $className, array $props = []): Component
    {
        return PamPhpRegistry::make($className, $props);
    }

    public static function theme(Theme $theme): void
    {
        self::$activeTheme = $theme;
        $theme->apply();
    }

    /**
     * Component render memoization (enabled by default). Components re-render
     * when their own properties, props, slots, tracked state/stores/signals
     * change or an event handler ran on them; disable to re-render the whole
     * tree on every event as in releases before 1.0.38.
     */
    public static function memoization(bool $enabled): void
    {
        \Pam\Native\Internal\DependencyTracker::memoization($enabled);
    }

    public static function activeTheme(): ?Theme
    {
        return self::$activeTheme;
    }

    public static function appearance(): UserInterfaceAppearance
    {
        return Runtime::windowMetrics()->appearance;
    }

    public static function windowMetrics(): WindowMetrics
    {
        return Runtime::windowMetrics();
    }

    public static function component(string $tag, string $view): void
    {
        TemplateRegistry::view($tag, $view);
    }

    /**
     * Subscribes to every uncaught runtime error (render, event, module result
     * and boot failures) in all build modes, e.g. to forward them to Sentry.
     * The report carries app-relative frames and whether the error was fatal.
     * Returns an id for offError().
     *
     * @param Closure(Throwable, RuntimeError): void $listener
     */
    public static function onError(Closure $listener): int
    {
        return ErrorReporter::listen($listener);
    }

    public static function offError(int $subscription): void
    {
        ErrorReporter::unsubscribe($subscription);
    }

    public static function onBack(Closure $handler): void
    {
        Runtime::onBack($handler);
    }

    public static function onAppState(Closure $handler): void
    {
        self::$appStateHandler = $handler;
        self::installStateDispatcher();
    }

    /**
     * Subscribes to foreground/background transitions, like React Native AppState.
     * Active = foreground and interactive, Inactive = transitioning or covered by
     * system UI, Background = not visible. Returns an id for offStateChange().
     *
     * @param Closure(AppState): void $listener
     */
    public static function onStateChange(Closure $listener): int
    {
        $id = self::$nextStateListener++;
        self::$stateListeners[$id] = $listener;
        self::installStateDispatcher();

        return $id;
    }

    public static function offStateChange(int $subscription): void
    {
        unset(self::$stateListeners[$subscription]);
    }

    /** Last state reported by the host, or null before the first transition. */
    public static function state(): ?AppState
    {
        return self::$currentState;
    }

    private static function installStateDispatcher(): void
    {
        Runtime::onAppState(static function (AppState $state): void {
            self::$currentState = $state;
            self::$appStateHandler?->__invoke($state);
            foreach (self::$stateListeners as $listener) {
                $listener($state);
            }
        });
    }

    public static function onDimensions(Closure $handler): void
    {
        Runtime::onDimensions($handler);
    }

    public static function onMemoryPressure(Closure $handler): void
    {
        Runtime::onMemoryPressure($handler);
    }
}
