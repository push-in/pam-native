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
use Pam\Native\Routing\MiddlewareRegistry;
use Pam\Native\Routing\RouteArguments;
use Pam\Native\Routing\RouteCollection;
use Pam\Native\Routing\RouteNavigator;
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

    /**
     * Boots the app. Accepts a root Renderable/closure, a screen class, or
     * the routes registered by routes.php: `App::run(require 'routes.php')`
     * (require returns 1, or the RouteCollection when the file returns one).
     *
     * @param Renderable|Closure|RouteCollection|class-string<Renderable>|int $root
     */
    public static function run(Renderable|Closure|RouteCollection|string|int $root): void
    {
        try {
            $root = self::resolveRoot($root);
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
     * Route middleware aliases: `App::middleware(['auth' => Authenticate::class])`,
     * then `Route::middleware('auth')` or `->middleware('auth')` in routes.php.
     *
     * @param array<string, class-string<Middleware>|Middleware|Closure> $aliases
     */
    public static function middleware(array $aliases): void
    {
        MiddlewareRegistry::alias($aliases);
    }

    /** @param Renderable|Closure|RouteCollection|class-string<Renderable>|int $root */
    private static function resolveRoot(Renderable|Closure|RouteCollection|string|int $root): Renderable|Closure
    {
        if ($root instanceof Renderable || $root instanceof Closure) return $root;
        if ($root instanceof RouteCollection || $root === 1 || ($root !== '' && is_string($root) && is_file($root))) {
            if (is_string($root)) {
                RouteCollection::reset();
                $loaded = require $root;
                if ($loaded instanceof RouteCollection) $root = $loaded;
            }
            $routes = $root instanceof RouteCollection ? $root : RouteCollection::current();
            $navigator = new RouteNavigator($routes);
            RouteCollection::activate($navigator);
            $navigator->listen();

            return $navigator->root();
        }
        if (is_string($root) && is_a($root, Renderable::class, true)) {
            $screen = RouteArguments::instantiate($root, [], $root);
            if (!$screen instanceof Component && method_exists($screen, 'setup')) $screen->setup();
            /** @var Renderable $screen */
            return $screen;
        }

        throw new \InvalidArgumentException('App::run() needs a Renderable, a screen class or the routes from routes.php.');
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
            Runtime::reportError($error, RuntimeErrorPhase::Boot);

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
