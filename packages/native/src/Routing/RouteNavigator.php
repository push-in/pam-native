<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use BackedEnum;
use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\Component;
use Pam\Native\Internal\DevWarnings;
use Pam\Native\Navigation\DeepLink;
use Pam\Native\Navigation\NavigationLinkHandler;
use Pam\Native\Navigation\NavigationEventType;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\RouteContext;
use Pam\Native\Navigation\Router;
use Pam\Native\Navigation\ScreenOptionLayer;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\TabNavigator;
use Pam\Native\Renderable;
use Pam\Native\Route;
use Pam\Native\System\Linking;
use Pam\Native\UI\Icon;
use Pam\Native\UI\Screen;
use Pam\Native\UI\StatusBar;
use ReflectionFunction;

use function array_key_exists;
use function count;
use function in_array;
use function is_scalar;
use function is_string;

/**
 * Runs a RouteCollection: one native stack (tabs groups are single entries),
 * middleware before every navigation, typed params and incoming links.
 */
final class RouteNavigator implements NavigationLinkHandler
{
    private const MAX_REDIRECTS = 8;

    private readonly Navigator $stack;
    /** @var array<string, TabNavigator> */
    private array $tabNavigators = [];
    /** @var list<array{0: DeepLink, 1: Route}> */
    private array $links = [];
    private ?int $linkSubscription = null;
    /** @var array<string, Component> tab and status-bar-wrapped screens, by route name */
    private array $tracked = [];
    private ?Component $focused = null;
    /**
     * Middleware verdicts of the navigation in progress, keyed by route and
     * params: the stack guard and the tab-press check reuse them, so the
     * middleware runs once per navigation.
     *
     * @var array<string, bool|Redirect>
     */
    private array $verdicts = [];
    private int $navigating = 0;
    /** @var list<\Pam\Native\Navigation\NavigationSubscription> kept alive: a dropped subscription unsubscribes */
    private array $subscriptions = [];
    /** @var list<array{0: string|BackedEnum, 1: array<string, mixed>, 2: RedirectMode}> */
    private array $pendingRedirects = [];

    public function __construct(private readonly RouteCollection $routes)
    {
        $this->navigating++;
        $initial = $this->bootRoute();
        // Same stack defaults as the pre-v2 Route::stack(): slide from right, 240 ms, fresh boot.
        $router = Router::stack($initial->routeName())
            ->persistence('routes')
            ->restoreState($routes->persist)
            ->transitions(NavigationTransition::SlideFromRight, 240);
        $fallback = null;
        foreach ($routes->all() as $route) {
            if ($route->kind === RouteKind::Tab) continue;
            $name = $route->routeName();
            $factory = $route->kind === RouteKind::Tabs
                ? fn (): TabNavigator => $this->tabNavigator($route)
                : fn (RouteContext $context): Renderable => $this->make($route, $context->all());
            $router = $router->route($name, $factory, self::options($route), $route->identity());
            $boundary = $route->boundary() ?? $routes->errorBoundary;
            if ($boundary !== null && $route->kind !== RouteKind::Tabs) $router = $router->errorBoundary($name, $boundary);
            if ($route->middleware() !== []) {
                $router = $router->guard(
                    $name,
                    fn (RouteContext $context): bool => $this->verdict($route, $context->all()) === true,
                );
            } elseif ($fallback === null && $route->kind !== RouteKind::Tabs) {
                $fallback = $name;
            }
            $keepAlive = $route->kind === RouteKind::Tabs ? true : $route->keepsAlive();
            if ($keepAlive !== false) $router = $router->keepAlive($name, $keepAlive instanceof Closure ? $keepAlive : null);
        }
        if ($fallback !== null) $router = $router->guardFallback($fallback);
        try {
            $this->stack = $router->build();
        } finally {
            $this->navigating--;
            $this->verdicts = [];
        }
        foreach ($routes->all() as $route) {
            if (!$route->linkable()) continue;
            foreach ([(string) $route->path, ...$route->aliases()] as $path) {
                $this->links[] = [new DeepLink($path, $route->routeName()), $route];
            }
        }
        \Pam\Native\Routing\Navigation::attach($this->stack);
        foreach ([NavigationEventType::Focus, NavigationEventType::State] as $type) {
            $this->subscriptions[] = $this->stack->addListener($type, fn () => $this->syncFocus());
        }
    }

    /**
     * The stack focuses the screens it renders itself; screens it cannot see
     * (tabs inside a tabs entry, screens wrapped for a status bar) get
     * onFocus/onBlur from here.
     */
    private function syncFocus(): void
    {
        $route = $this->routes->find($this->stack->currentRoute());
        $name = $route?->routeName();
        if ($route !== null && $route->kind === RouteKind::Tabs && isset($this->tabNavigators[$route->routeName()])) {
            $name = $this->tabNavigators[$route->routeName()]->selectedTab();
        }
        $next = $name === null ? null : ($this->tracked[$name] ?? null);
        if ($next === $this->focused) return;
        $this->focused?->__pamNavigationFocus(false);
        $this->focused = $next;
        $next?->__pamNavigationFocus(true);
    }

    /** The native stack, for pre-v2 APIs that take a Navigator. */
    public function root(): Navigator
    {
        return $this->stack;
    }

    /** What App::run() mounts: the Route::layout() component around the stack, or the stack. */
    public function view(): Renderable
    {
        $layout = $this->routes->layout;
        if ($layout === null) return $this->stack;
        $services = [$this->stack, $this];
        if ($layout instanceof Closure) {
            $view = $layout(...RouteArguments::resolve(new ReflectionFunction($layout), [], 'layout', strict: false, services: $services));
        } else {
            $view = RouteArguments::instantiate($layout, [], 'layout', $services);
            if (!$view instanceof Component && method_exists($view, 'setup')) $view->setup(...RouteArguments::for($view));
        }
        if (!$view instanceof Renderable) {
            throw new InvalidArgumentException('Route::layout() must produce a Renderable, '.get_debug_type($view).' given.');
        }

        return $view;
    }

    public function routes(): RouteCollection
    {
        return $this->routes;
    }

    /** Subscribes to incoming links when the app has linkable routes (one native listener). */
    public function listen(): void
    {
        if (!$this->routes->linking || $this->links === [] || $this->linkSubscription !== null) return;
        try {
            Linking::initial(function (?string $url): void {
                if ($url !== null) $this->open($url);
            }, static function (): void {});
            $this->linkSubscription = Linking::listen(function (string $url): void {
                $this->open($url);
            });
        } catch (LogicException) {
            // The app already owns the single native Linking listener.
            DevWarnings::warn(
                'routes.php has linkable routes but the app already listens to Linking, so incoming links are not routed automatically. '
                .'Call Route::open($uri) from your listener, or declare Route::linking(false) in routes.php to silence this',
                'Pam\\Native\\Routing\\RouteNavigator::listen',
            );
        }
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function navigate(
        string|BackedEnum|TypedRoute $target,
        array $params = [],
        RedirectMode $mode = RedirectMode::Navigate,
        int $redirects = 0,
    ): bool {
        if ($target instanceof TypedRoute) {
            $params = [...$target->params, ...$params];
            $target = $target->name;
        }
        $route = $this->routes->get($target);
        $params = $this->validate($route, $params);
        $this->navigating++;
        try {
            $outcome = $this->verdict($route, $params);
            if ($outcome instanceof Redirect) {
                if ($redirects >= self::MAX_REDIRECTS) {
                    throw new LogicException("Route {$route->routeName()} redirects in a loop.");
                }

                return $this->navigate($outcome->route, $outcome->params, $outcome->mode, $redirects + 1);
            }
            if ($outcome === false) return false;

            return $this->perform($route, $params, $mode);
        } finally {
            if (--$this->navigating === 0) {
                $this->verdicts = [];
                $this->flushRedirects();
            }
        }
    }

    /**
     * Runs a route's middleware once per navigation: while a navigation is in
     * progress the verdict is cached for the same route and params; outside
     * one (history refresh, restore) it is computed fresh.
     *
     * @param array<string, mixed> $params
     */
    private function verdict(Route $route, array $params): bool|Redirect
    {
        if ($route->middleware() === []) return true;
        ksort($params);
        $key = $route->routeName().'|'.serialize($params);
        if ($this->navigating > 0 && array_key_exists($key, $this->verdicts)) return $this->verdicts[$key];
        $outcome = MiddlewareRegistry::run($route->withParams($params));
        if ($this->navigating > 0) $this->verdicts[$key] = $outcome;

        return $outcome;
    }

    /**
     * A tap on a tab bar item goes through the tab's middleware like
     * navigate(): a denied tab stays unselected, a redirect navigates.
     */
    private function guardTabPress(\Pam\Native\Navigation\NavigationEvent $event): void
    {
        $route = $this->routes->find((string) ($event->data['route'] ?? $event->target));
        if ($route === null || $route->kind !== RouteKind::Tab) return;
        $this->navigating++;
        try {
            $outcome = $this->verdict($route, []);
        } finally {
            if (--$this->navigating === 0) $this->verdicts = [];
        }
        if ($outcome === true) return;
        $event->preventDefault();
        if ($outcome instanceof Redirect) {
            // Navigate after the tab bar finished handling this press.
            $this->pendingRedirects[] = [$outcome->route, $outcome->params, $outcome->mode];
            if ($this->navigating === 0) $this->flushRedirects();
        }
    }

    private function flushRedirects(): void
    {
        while (($next = array_shift($this->pendingRedirects)) !== null) {
            $this->navigate($next[0], $next[1], $next[2]);
        }
    }

    public function back(): bool
    {
        return $this->stack->goBack();
    }

    public function refresh(): bool
    {
        return $this->stack->refreshConditions();
    }

    /** The focused route (a tab inside a tabs group) with its params. */
    public function current(): ?Route
    {
        $context = $this->stack->current();
        $route = $this->routes->find($context->name);
        if ($route === null) return null;
        if ($route->kind === RouteKind::Tabs && isset($this->tabNavigators[$route->routeName()])) {
            $tab = $this->routes->find($this->tabNavigators[$route->routeName()]->selectedTab());
            if ($tab !== null) return $tab;
        }

        return $route->withParams($context->all());
    }

    public function open(string $uri): bool
    {
        if ($uri === '' || strlen($uri) > 8_192) return false;
        $parts = parse_url($uri);
        if ($parts === false) return false;
        $path = $parts['path'] ?? '/';
        $scheme = strtolower((string) ($parts['scheme'] ?? ''));
        $candidates = [$path === '' ? '/' : $path];
        if ($scheme !== '' && !in_array($scheme, ['http', 'https'], true)) {
            $host = trim((string) ($parts['host'] ?? ''), '/');
            if ($host !== '') array_unshift($candidates, '/'.$host.($path === '/' ? '' : $path));
        }
        $query = [];
        if (isset($parts['query'])) parse_str($parts['query'], $query);
        foreach ($candidates as $candidate) {
            foreach ($this->links as [$link, $route]) {
                $params = $link->match($candidate);
                if ($params === null) continue;
                $accepted = $this->acceptedNames($route);
                foreach ($query as $key => $value) {
                    if (is_string($key) && is_scalar($value) && !array_key_exists($key, $params) && ($accepted === null || in_array($key, $accepted, true))) {
                        $params[$key] = (string) $value;
                    }
                }
                try {
                    return $this->navigate($route->routeName(), $params);
                } catch (InvalidArgumentException) {
                    return false;
                }
            }
        }

        return false;
    }

    public function currentPath(): ?string
    {
        $current = $this->current();
        if ($current === null || $current->path === null) return null;

        return (new DeepLink($current->path, $current->routeName()))->build($current->params());
    }

    public function currentUrl(): ?string
    {
        return $this->currentPath();
    }

    /** @internal Builds a screen for a route (tests use it directly). @param array<string, mixed> $params */
    public function make(Route $route, array $params): Renderable
    {
        $screen = $route->screen;
        $name = $route->routeName();
        // Navigator, RouteContext and RouteNavigator are injected by type.
        $services = [$this->stack, new RouteContext($name, $params), $this];
        if ($screen instanceof Closure) {
            $result = $screen(...RouteArguments::resolve(new ReflectionFunction($screen), $params, $name, strict: false, services: $services));
        } elseif (is_string($screen)) {
            $result = RouteArguments::instantiate($screen, $params, $name, $services);
            if ($result instanceof Component) {
                $result->__pamRouteParams($params, $services[1]);
            } elseif (method_exists($result, 'setup')) {
                $result->setup(...RouteArguments::for($result));
            }
        } else {
            throw new LogicException("Route {$name} has no screen.");
        }
        if (!$result instanceof Renderable) {
            throw new InvalidArgumentException("Route {$name} screen must be Renderable, ".get_debug_type($result).' given.');
        }
        $style = $route->statusBarStyle();
        if ($style === null && $route->kind !== RouteKind::Tab) return $result;
        if ($result instanceof Component) {
            $this->tracked[$name] = $result;
            $this->syncFocus();
        }
        if ($style === null) return $result;

        return Screen::make(StatusBar::make(null, $style, $route->statusBarHidden()), $result);
    }

    private function tabNavigator(Route $host): TabNavigator
    {
        $name = $host->routeName();
        if (isset($this->tabNavigators[$name])) return $this->tabNavigators[$name];
        $group = $this->routes->tabGroups()[$name] ?? throw new LogicException("Tabs group {$name} is missing.");
        $initial = $group->tabs[0];
        foreach ($group->tabs as $tab) if ($tab->isInitial()) $initial = $tab;
        $router = Router::tabs($initial->routeName())->persistence($name);
        foreach ($group->tabs as $tab) {
            $router = $router->tab(
                $tab->routeName(),
                $tab->labelText(),
                fn (): Renderable => $this->make($tab, []),
                self::icon($tab->iconName()),
            );
        }
        if ($group->presentation !== null) $router = $router->presentation($group->presentation);
        if ($group->backBehavior !== null) $router = $router->behavior($group->backBehavior);

        $navigator = $this->tabNavigators[$name] = $router->build();
        $this->subscriptions[] = $navigator->addListener(NavigationEventType::State, fn () => $this->syncFocus());
        $this->subscriptions[] = $navigator->addListener(NavigationEventType::TabPress, fn (\Pam\Native\Navigation\NavigationEvent $event) => $this->guardTabPress($event));

        return $navigator;
    }

    /** Ionicons glyph when the app ships the font; label-only tabs otherwise. */
    private static function icon(?string $name): ?Renderable
    {
        if ($name === null) return null;
        try {
            return Icon::make($name, size: 22.0);
        } catch (\RuntimeException) {
            return null;
        }
    }

    /** @param array<string, string|int|float|bool|null> $params */
    private function perform(Route $route, array $params, RedirectMode $mode): bool
    {
        if ($route->kind === RouteKind::Tab) {
            $host = $this->routes->host($route);
            // The tab inherits its host's middleware and already passed it.
            $this->verdicts[$host->routeName().'|'.serialize([])] = true;
            $this->stackAction($host, [], $mode);
            $tabs = $this->tabNavigator($host);

            return $tabs->select($route->routeName()) || $tabs->selectedTab() === $route->routeName();
        }

        return $this->stackAction($route, $params, $mode);
    }

    /** @param array<string, string|int|float|bool|null> $params */
    private function stackAction(Route $route, array $params, RedirectMode $mode): bool
    {
        $name = $route->routeName();
        match ($mode) {
            RedirectMode::Navigate => $this->stack->navigate($name, $params),
            RedirectMode::Push => $this->stack->push($name, $params),
            RedirectMode::Replace => $this->stack->replace($name, $params),
            RedirectMode::Reset => $this->stack->reset($name, $params),
        };

        return true;
    }

    /**
     * @param array<string, string|int|float|bool|null> $params
     * @return array<string, string|int|float|bool|null>
     */
    private function validate(Route $route, array $params): array
    {
        if ($route->kind === RouteKind::Tabs) {
            if ($params !== []) throw new InvalidArgumentException("Tabs group {$route->routeName()} takes no parameters.");
            return [];
        }
        if (!is_string($route->screen)) return $params;

        return RouteArguments::validate($route->screen, $params, $route->routeName(), $route->pathParameters());
    }

    /** @return list<string>|null null: every query key */
    private function acceptedNames(Route $route): ?array
    {
        $names = $route->pathParameters();
        if (is_string($route->screen) && class_exists($route->screen)) {
            foreach (RouteArguments::signature($route->screen) as $parameter) $names[] = $parameter['name'];
        } elseif ($route->screen instanceof Closure) {
            // Same rule as the compiler: scalar closure parameters are route
            // params, services are not; a closure taking the RouteContext reads
            // any param itself, so every query key is kept (pre-v2 behaviour).
            if (RouteArguments::readsContext($route->screen)) return null;
            foreach ((new ReflectionFunction($route->screen))->getParameters() as $parameter) {
                if (!RouteArguments::injectable($parameter)) $names[] = $parameter->getName();
            }
        }

        return array_values(array_unique($names));
    }

    private function bootRoute(): Route
    {
        $route = $this->routes->initial();
        $params = [];
        for ($redirects = 0; $redirects <= self::MAX_REDIRECTS; $redirects++) {
            $outcome = $this->verdict($route, $params);
            if (!$outcome instanceof Redirect) return $route;
            $route = $this->routes->get($outcome->route);
            if ($route->kind === RouteKind::Tab) $route = $this->routes->host($route);
            $params = $outcome->params;
        }

        throw new LogicException('The initial route redirects in a loop.');
    }

    private static function options(Route $route): ?Closure
    {
        $layers = $route->screenOptions();
        if ($layers === []) return null;

        return static function (RouteContext $context, ScreenOptions $inherited) use ($layers): ScreenOptions {
            $resolved = $inherited;
            foreach ($layers as $layer) $resolved = ScreenOptionLayer::apply($layer, $context, $resolved);

            return $resolved;
        };
    }

    /** @internal Number of linkable routes (tests). */
    public function linkCount(): int
    {
        return count($this->links);
    }
}
