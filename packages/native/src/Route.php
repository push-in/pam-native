<?php

declare(strict_types=1);

namespace Pam\Native;

use BackedEnum;
use BadMethodCallException;
use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\Navigation\NavigationGestureDirection;
use Pam\Native\Navigation\NavigationOrientation;
use Pam\Native\Navigation\NavigationPresentation;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\ScreenOptionsPatch;
use Pam\Native\Routing\RoutePreset;
use Pam\Native\Routing\PendingRouteGroup;
use Pam\Native\Routing\RedirectMode;
use Pam\Native\Routing\RouteCollection;
use Pam\Native\Routing\RouteKind;
use Pam\Native\Routing\RouteNavigator;
use Pam\Native\Routing\TabGroup;
use Pam\Native\Routing\TypedRoute;

use function count;
use function is_array;
use function in_array;
use function is_string;

/**
 * Laravel-style routes for PAM apps (`routes.php`), and the route a
 * middleware receives.
 *
 *     Route::screen('/login', LoginScreen::class)->name('login');
 *     Route::middleware('auth')->group(function () {
 *         Route::tabs(function () {
 *             Route::screen('/chats', ChatsScreen::class)->name('chats')->icon('chatbubbles');
 *         });
 *         Route::screen('/chat/{id}', ChatScreen::class)->name('chat');
 *         Route::modal('/settings', SettingsScreen::class)->name('settings');
 *     });
 *
 * `routes.php` returns nothing: registrations are collected and
 * `App::run(require __DIR__.'/routes.php')` boots them. The PAM compiler
 * reads the same file to generate typed navigation and deep links.
 *
 * Static: screen(), modal(), tabs(), group(), middleware(), name(), prefix(),
 * navigate(), back(), current(), path(), refresh().
 * Instance (chained on a registration): name(), middleware(), icon(), label(),
 * title(), gestures(), presentation(), orientation(), statusBar(),
 * transition(), sheet(), secure(), keepAlive(), initial(), deepLink().
 *
 * @method static PendingRouteGroup middleware(string|list<string>|Closure $middleware)
 * @method static PendingRouteGroup name(string $prefix)
 * @method static PendingRouteGroup prefix(string $path)
 */
final class Route
{
    /** @var list<string|Closure> */
    private array $middleware = [];
    /** @var list<ScreenOptionsPatch|ScreenOptions|Closure> */
    private array $options = [];
    /** @var list<string> */
    private array $aliases = [];
    private ?Closure $identity = null;
    private ?Closure $errorBoundary = null;
    private ?string $name = null;
    private ?string $icon = null;
    private ?string $label = null;
    private ?StatusBarAppearance $statusBar = null;
    private bool $statusBarHidden = false;
    private bool|Closure $keepAlive = false;
    private bool $initial = false;
    private bool $deepLink = true;
    /** @var array<string, string|int|float|bool|null> */
    private array $params = [];

    /**
     * @internal Use Route::screen()/modal()/tabs().
     * @param class-string<Renderable>|Closure|null $screen
     */
    public function __construct(
        public readonly RouteKind $kind,
        public readonly ?string $path,
        public readonly string|Closure|null $screen,
        public readonly ?string $tabs = null,
    ) {
        if ($path !== null && ($path === '' || $path[0] !== '/' || strlen($path) > 512)) {
            throw new InvalidArgumentException("Route paths must start with / (got \"{$path}\").");
        }
    }

    // ----------------------------------------------------------------- registration

    /** @param class-string<Renderable>|Closure $screen */
    public static function screen(string $path, string|Closure $screen): self
    {
        return RouteCollection::current()->add(RouteKind::Screen, $path, $screen);
    }

    /** A route presented modally (presentation: modal; `->presentation('sheet')` for a sheet). */
    public static function modal(string $path, string|Closure $screen): self
    {
        return RouteCollection::current()->add(RouteKind::Modal, $path, $screen)
            ->presentation(NavigationPresentation::Modal);
    }

    /**
     * Bottom tabs: every Route::screen() inside becomes a tab; the group is
     * one stack entry, so stack routes push over the tab bar.
     */
    public static function tabs(Closure $routes, string $name = 'tabs'): TabGroup
    {
        return RouteCollection::current()->tabs($name, $routes);
    }

    /**
     * Attributes: middleware, name, prefix, transition (NavigationTransition or
     * [NavigationTransition, ms]), gestures (bool), presentation, orientation,
     * options (ScreenOptionsPatch|Closure), errorBoundary (Closure). A
     * RoutePreset or ScreenOptionsPatch is accepted as the options shorthand.
     *
     * @param array<string, mixed>|RoutePreset|ScreenOptionsPatch|Closure $attributes
     */
    public static function group(array|RoutePreset|ScreenOptionsPatch|Closure $attributes, ?Closure $routes = null): void
    {
        if ($attributes instanceof Closure) {
            (new PendingRouteGroup())->group($attributes);
            return;
        }
        $routes ??= throw new InvalidArgumentException('Route::group() needs a closure.');
        if (!is_array($attributes)) {
            (new PendingRouteGroup())->options($attributes)->group($routes);
            return;
        }
        PendingRouteGroup::fromAttributes($attributes)->group($routes);
    }

    /**
     * Root layout (like a Laravel/Expo layout): the component wraps the whole
     * stack. Its constructor/setup() receives the Navigator (or the
     * RouteNavigator) by type, so it can draw gates, banners and hosts
     * around or over the screens.
     *
     * @param class-string<Renderable>|Closure $layout
     */
    public static function layout(string|Closure $layout, ?Closure $routes = null): void
    {
        RouteCollection::current()->layout = $layout;
        $routes?->__invoke();
    }

    /** Opt out of system deep links (`myapp://...`) for the whole router. */
    public static function linking(bool $enabled): void
    {
        RouteCollection::current()->linking = $enabled;
    }

    /** Restore the persisted stack on a cold start (default: always boot from the initial route). */
    public static function persist(bool $enabled = true): void
    {
        RouteCollection::current()->persist = $enabled;
    }

    /** @param list<mixed> $arguments */
    public static function __callStatic(string $method, array $arguments): mixed
    {
        return match ($method) {
            'middleware' => (new PendingRouteGroup())->middleware(...$arguments),
            'name' => (new PendingRouteGroup())->name(...$arguments),
            'prefix' => (new PendingRouteGroup())->prefix(...$arguments),
            'errorBoundary' => RouteCollection::current()->errorBoundary = $arguments[0] ?? null,
            default => throw new BadMethodCallException("Route::{$method}() does not exist."),
        };
    }

    // ------------------------------------------------------------------ navigation

    /**
     * Navigate by route name, screen class or generated TypedRoute. Middleware
     * runs first and may redirect. Params are validated against the screen's
     * setup()/constructor parameters.
     *
     * @param array<string, string|int|float|bool|null> $params
     */
    public static function navigate(
        string|BackedEnum|TypedRoute $route,
        array $params = [],
        RedirectMode|string $mode = RedirectMode::Navigate,
        ?NavigationTransition $transition = null,
        bool $merge = false,
        ?int $duration = null,
    ): bool {
        return self::navigator()->navigate($route, $params, $mode, 0, $transition, $merge, $duration);
    }

    /**
     * Goes back one step, like the system Back: the open child navigator
     * (tabs, nested stack) first, then this stack. A Back that a screen
     * cancels (beforeRemove, "leave without saving?") is still consumed and
     * returns true, so it never falls through to closing the app; false only
     * when there is nothing to go back to. Navigator::pop() (legacy) returns
     * false when the screen cancels and ignores child navigators.
     */
    public static function back(): bool
    {
        return self::navigator()->back();
    }

    /** The focused route, with its params. */
    public static function current(): ?self
    {
        return RouteCollection::active()?->current();
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public static function path(string|BackedEnum $route, array $params = []): ?string
    {
        return RouteCollection::current()->path($route, $params);
    }

    /** The registered route definition (by name or screen class). */
    public static function get(string|BackedEnum $route): self
    {
        return RouteCollection::current()->get($route);
    }

    public static function has(string|BackedEnum $route): bool
    {
        return RouteCollection::current()->find($route) !== null;
    }

    /** Re-runs middleware over the history (after login/logout) and drops what is no longer allowed. */
    public static function refresh(): bool
    {
        return self::navigator()->refresh();
    }

    /** Handles an incoming link (`myapp://chat/5`, `https://host/chat/5`). */
    public static function open(string $uri): bool
    {
        return self::navigator()->open($uri);
    }

    /** @internal Starts a fresh registry (tests, hot reload). */
    public static function flush(): void
    {
        RouteCollection::reset();
    }

    private static function navigator(): RouteNavigator
    {
        return RouteCollection::active()
            ?? throw new LogicException('No routes are running; call App::run(require __DIR__.\'/routes.php\') first.');
    }

    // --------------------------------------------------------------- chained options

    /** @param list<mixed> $arguments */
    public function __call(string $method, array $arguments): mixed
    {
        return match ($method) {
            'name' => $arguments === [] ? $this->routeName() : $this->named(...$arguments),
            'middleware' => $arguments === [] ? $this->middleware : $this->withMiddleware(...$arguments),
            'errorBoundary' => $this->withErrorBoundary(...$arguments),
            default => throw new BadMethodCallException("Route->{$method}() does not exist."),
        };
    }

    public function icon(string $icon): self
    {
        $this->icon = $icon;

        return $this;
    }

    /** Tab label (default: the route name, capitalised). */
    public function label(string $label): self
    {
        $this->label = $label;

        return $this;
    }

    public function title(string $title): self
    {
        return $this->option('title', $title);
    }

    /** Swipe-back / swipe-to-dismiss gestures. */
    public function gestures(
        bool $enabled = true,
        NavigationGestureDirection $direction = NavigationGestureDirection::Horizontal,
        bool $fullScreen = false,
    ): self {
        $this->options[] = ScreenOptionsPatch::from([
            'gestureEnabled' => $enabled,
            'gestureDirection' => $direction,
            'fullScreenGestureEnabled' => $fullScreen,
        ]);

        return $this;
    }

    /** 'card' | 'modal' | 'fullScreen' | 'transparent' | 'sheet' | 'contained', or the enum. */
    public function presentation(string|NavigationPresentation $presentation): self
    {
        return $this->option('presentation', $presentation instanceof NavigationPresentation
            ? $presentation
            : match (strtolower($presentation)) {
                'card', 'push' => NavigationPresentation::Card,
                'modal' => NavigationPresentation::Modal,
                'fullscreen', 'fullscreenmodal', 'full-screen' => NavigationPresentation::FullScreenModal,
                'transparent', 'transparentmodal' => NavigationPresentation::TransparentModal,
                'sheet', 'formsheet', 'form-sheet' => NavigationPresentation::FormSheet,
                'contained', 'containedmodal' => NavigationPresentation::ContainedModal,
                default => throw new InvalidArgumentException("Unknown presentation {$presentation}."),
            });
    }

    /** 'portrait' | 'landscape' | 'all' | 'portrait-up' | 'landscape-left' | ..., or the enum. */
    public function orientation(string|NavigationOrientation $orientation): self
    {
        return $this->option('orientation', $orientation instanceof NavigationOrientation
            ? $orientation
            : match (strtolower($orientation)) {
                'default' => NavigationOrientation::PlatformDefault,
                'all' => NavigationOrientation::All,
                'portrait' => NavigationOrientation::Portrait,
                'portrait-up' => NavigationOrientation::PortraitUp,
                'portrait-down' => NavigationOrientation::PortraitDown,
                'landscape' => NavigationOrientation::Landscape,
                'landscape-left' => NavigationOrientation::LandscapeLeft,
                'landscape-right' => NavigationOrientation::LandscapeRight,
                default => throw new InvalidArgumentException("Unknown orientation {$orientation}."),
            });
    }

    /** Status bar icons over this screen: 'light' | 'dark' (or the enum), optionally hidden. */
    public function statusBar(string|StatusBarAppearance $style, bool $hidden = false): self
    {
        $this->statusBar = $style instanceof StatusBarAppearance ? $style : match (strtolower($style)) {
            'light', 'light-content' => StatusBarAppearance::Light,
            'dark', 'dark-content' => StatusBarAppearance::Dark,
            default => throw new InvalidArgumentException("Unknown status bar style {$style}."),
        };
        $this->statusBarHidden = $hidden;

        return $this;
    }

    public function transition(NavigationTransition $animation, ?int $durationMs = null): self
    {
        $options = ['animation' => $animation];
        if ($durationMs !== null) $options['animationDurationMs'] = $durationMs;
        $this->options[] = ScreenOptionsPatch::from($options);

        return $this;
    }

    /** @param list<float>|null $detents */
    public function sheet(?array $detents = null, bool $grabber = true, ?float $cornerRadius = null): self
    {
        $options = ['presentation' => NavigationPresentation::FormSheet, 'sheetGrabberVisible' => $grabber];
        if ($detents !== null) $options['sheetAllowedDetents'] = $detents;
        if ($cornerRadius !== null) $options['sheetCornerRadius'] = $cornerRadius;
        $this->options[] = ScreenOptionsPatch::from($options);

        return $this;
    }

    /** Blocks screenshots/recording while this route is visible. */
    public function secure(bool $enabled = true): self
    {
        return $this->option('secure', $enabled);
    }

    /**
     * Keep the screen mounted (state, scroll) when it leaves the stack; a
     * closure limits it to some entries: `fn (RouteContext $r) => !$r->has('id')`.
     */
    public function keepAlive(bool|Closure $when = true): self
    {
        $this->keepAlive = $when;

        return $this;
    }

    /** Another path (and deep link) for this route, e.g. ->alias('/group/{chatId}'). Repeatable. */
    public function alias(string $path): self
    {
        $path = RouteCollection::current()->prefixed($path);
        new \Pam\Native\Navigation\DeepLink($path, 'probe');
        if (!in_array($path, $this->aliases, true) && $path !== $this->path) $this->aliases[] = $path;

        return $this;
    }

    /**
     * Route identity: navigate() to an entry with the same identity goes
     * back to it instead of pushing another, e.g.
     * `->unique(fn (RouteContext $r) => 'chat:'.$r->chatId)`.
     *
     * @param Closure(\Pam\Native\Navigation\RouteContext): (string|int|null) $identity
     */
    public function unique(Closure $identity): self
    {
        $this->identity = $identity;

        return $this;
    }

    /** Boot route (default: the `/` route, else the first tabs group, else the first route). */
    public function initial(bool $initial = true): self
    {
        $this->initial = $initial;

        return $this;
    }

    /** Exclude this route from deep links / app links. */
    public function deepLink(bool $enabled = true): self
    {
        $this->deepLink = $enabled;

        return $this;
    }

    public function options(ScreenOptionsPatch|ScreenOptions|RoutePreset|Closure $options): self
    {
        $this->options[] = $options instanceof RoutePreset ? $options->options : $options;

        return $this;
    }

    // ------------------------------------------------------------- route as a value

    public function routeName(): string
    {
        return $this->name ?? throw new LogicException('The route has no name yet.');
    }

    /** @return array<string, string|int|float|bool|null> */
    public function params(): array
    {
        return $this->params;
    }

    public function param(string $name, mixed $default = null): mixed
    {
        return $this->params[$name] ?? $default;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function withParams(array $params): self
    {
        $copy = clone $this;
        $copy->params = $params;

        return $copy;
    }

    public function is(string ...$names): bool
    {
        foreach ($names as $pattern) {
            if (fnmatch($pattern, (string) $this->name)) return true;
        }

        return false;
    }

    /** @internal */
    public function iconName(): ?string
    {
        return $this->icon;
    }

    /** @internal */
    public function labelText(): string
    {
        return $this->label ?? ucfirst(str_replace(['-', '_', '.'], ' ', (string) $this->name));
    }

    /** @internal */
    public function statusBarStyle(): ?StatusBarAppearance
    {
        return $this->statusBar;
    }

    /** @internal */
    public function statusBarHidden(): bool
    {
        return $this->statusBarHidden;
    }

    /** @internal @return list<ScreenOptionsPatch|ScreenOptions|Closure> */
    public function screenOptions(): array
    {
        return $this->options;
    }

    /** @internal */
    public function keepsAlive(): bool|Closure
    {
        return $this->keepAlive;
    }

    /** @internal @return list<string> */
    public function aliases(): array
    {
        return $this->aliases;
    }

    /** @internal */
    public function identity(): ?Closure
    {
        return $this->identity;
    }

    /** @internal */
    public function boundary(): ?Closure
    {
        return $this->errorBoundary;
    }

    /**
     * @internal Group attributes, applied under the route's own.
     * @param list<ScreenOptionsPatch|ScreenOptions|Closure> $options
     */
    public function inheritGroup(array $options, ?Closure $errorBoundary): void
    {
        $this->options = [...$options, ...$this->options];
        $this->errorBoundary ??= $errorBoundary;
    }

    /** @param Closure(\Throwable, \Pam\Native\Navigation\RouteContext, Closure(): void): Renderable $boundary */
    private function withErrorBoundary(Closure $boundary): self
    {
        $this->errorBoundary = $boundary;

        return $this;
    }

    /** @internal */
    public function isInitial(): bool
    {
        return $this->initial;
    }

    /** @internal */
    public function linkable(): bool
    {
        return $this->deepLink && $this->path !== null && $this->kind !== RouteKind::Tabs;
    }

    /** @internal Placeholders of the path and aliases, e.g. ['id'] for /chat/{id}. @return list<string> */
    public function pathParameters(): array
    {
        if ($this->path === null) return [];
        preg_match_all('/\{([A-Za-z][A-Za-z0-9_]{0,63})[?*]?\}/', implode('/', [$this->path, ...$this->aliases]), $matches);

        return array_values(array_unique($matches[1]));
    }

    /** @internal */
    public function named(string $name): self
    {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/D', $name) !== 1) {
            throw new InvalidArgumentException("Route names must be safe identifiers (got \"{$name}\").");
        }
        RouteCollection::current()->rename($this, RouteCollection::current()->namePrefix().$name);

        return $this;
    }

    /** @internal Sets the name without registry bookkeeping. */
    public function assignName(string $name): void
    {
        $this->name = $name;
    }

    /** @internal */
    public function hasName(): bool
    {
        return $this->name !== null;
    }

    /** @param string|Closure|list<string|Closure> ...$middleware */
    private function withMiddleware(string|Closure|array ...$middleware): self
    {
        foreach ($middleware as $entry) {
            foreach (is_array($entry) ? $entry : [$entry] as $item) $this->middleware[] = $item;
        }

        return $this;
    }

    /** @internal @param list<string|Closure> $middleware */
    public function inheritMiddleware(array $middleware): void
    {
        $this->middleware = [...$middleware, ...$this->middleware];
    }

    private function option(string $name, mixed $value): self
    {
        $this->options[] = ScreenOptionsPatch::one($name, $value);

        return $this;
    }

    /** @internal Number of registered option layers (tests). */
    public function optionCount(): int
    {
        return count($this->options);
    }
}
