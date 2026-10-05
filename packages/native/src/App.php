<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
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

    private function __construct()
    {
    }

    public static function run(Renderable|Closure $root): void
    {
        try {
            PluginManager::boot();
            if ($root instanceof TabNavigator) {
                Runtime::onDimensions($root->dimensions(...));
            }
            Runtime::boot($root);
        } catch (Throwable $error) {
            Runtime::reportError($error);

            throw $error;
        }
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
        try {
            PamPhpRegistry::discover(
                $path,
                $cachePath ?? getcwd().'/.pam/components',
            );
        } catch (Throwable $error) {
            Runtime::reportError($error);

            throw $error;
        }
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
