<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use BackedEnum;
use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\Navigation\DeepLink;
use Pam\Native\Route;

use function array_pop;
use function count;
use function is_string;

/**
 * Routes registered by `routes.php`. `App::run()` builds a RouteNavigator
 * from it; the route compiler reads it to generate typed navigation.
 */
final class RouteCollection
{
    private static ?self $current = null;
    private static ?RouteNavigator $active = null;

    /** @var list<Route> registration order */
    private array $routes = [];
    /** @var array<string, Route> */
    private array $named = [];
    /** @var array<string, TabGroup> */
    private array $tabGroups = [];
    /** @var list<array{middleware: list<string|Closure>, name: string, prefix: string, options: list<mixed>, errorBoundary: ?Closure}> */
    private array $groups = [];
    /** @var class-string<\Pam\Native\Renderable>|Closure|null Route::layout() */
    public string|Closure|null $layout = null;
    /** Route::errorBoundary(): default boundary of every route without its own. */
    public ?Closure $errorBoundary = null;
    private ?TabGroup $openTabs = null;
    public bool $linking = true;
    public bool $persist = false;

    public static function current(): self
    {
        return self::$current ??= new self();
    }

    /** @internal */
    public static function reset(): void
    {
        self::$current = null;
        self::$active = null;
    }

    /** @internal */
    public static function activate(?RouteNavigator $navigator): void
    {
        self::$active = $navigator;
    }

    public static function active(): ?RouteNavigator
    {
        return self::$active;
    }

    /** @internal */
    public function add(RouteKind $kind, string $path, string|Closure $screen): Route
    {
        if (is_string($screen) && preg_match('/^\\\\?[A-Za-z_][A-Za-z0-9_]*(\\\\[A-Za-z_][A-Za-z0-9_]*)*$/D', $screen) !== 1) {
            throw new InvalidArgumentException("Route {$path} needs a screen class name or closure.");
        }
        $path = $this->prefixed($path);
        new DeepLink($path, 'probe'); // validates the pattern syntax early
        if ($this->openTabs !== null && $kind !== RouteKind::Screen) {
            throw new LogicException('Only Route::screen() can be declared inside Route::tabs().');
        }
        $route = new Route(
            $this->openTabs !== null ? RouteKind::Tab : $kind,
            $path,
            is_string($screen) ? ltrim($screen, '\\') : $screen,
            $this->openTabs?->host->routeName(),
        );
        $route->inheritMiddleware($this->activeMiddleware());
        $this->inheritGroups($route);
        $this->routes[] = $route;
        $base = $this->namePrefix().self::nameFromPath($path);
        $name = $base;
        for ($suffix = 2; isset($this->named[$name]); $suffix++) $name = $base.'-'.$suffix;
        $this->rename($route, $name);
        if ($this->openTabs !== null) $this->openTabs->tabs[] = $route;

        return $route;
    }

    /** @internal */
    public function tabs(string $name, Closure $routes): TabGroup
    {
        if ($this->openTabs !== null) {
            throw new LogicException('Route::tabs() cannot be nested.');
        }
        $host = new Route(RouteKind::Tabs, null, null);
        $host->inheritMiddleware($this->activeMiddleware());
        $this->routes[] = $host;
        $this->rename($host, $this->namePrefix().$name);
        $group = new TabGroup($host);
        $this->openTabs = $group;
        try {
            $routes();
        } finally {
            $this->openTabs = null;
        }
        if ($group->tabs === []) {
            throw new LogicException("Route::tabs('{$name}') declares no tabs.");
        }
        $this->tabGroups[$host->routeName()] = $group;

        return $group;
    }

    /** @internal Assigns or changes a route name, keeping names unique. */
    public function rename(Route $route, string $name): void
    {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/D', $name) !== 1) {
            throw new InvalidArgumentException("Route names must be safe identifiers (got \"{$name}\").");
        }
        if ($route->hasName()) {
            $previous = $route->routeName();
            if ($previous === $name) return;
            unset($this->named[$previous]);
            if (isset($this->tabGroups[$previous])) {
                $this->tabGroups[$name] = $this->tabGroups[$previous];
                unset($this->tabGroups[$previous]);
            }
        }
        if (isset($this->named[$name])) {
            $other = $this->named[$name];
            throw new InvalidArgumentException(
                "Route name {$name} is already used by ".($other->path ?? 'a tabs group').'; give one of them ->name(...).',
            );
        }
        $route->assignName($name);
        $this->named[$name] = $route;
    }

    /** @param list<string|Closure> $middleware @param list<mixed> $options @internal */
    public function pushGroup(array $middleware, string $name, string $prefix, array $options = [], ?Closure $errorBoundary = null): void
    {
        $this->groups[] = compact('middleware', 'name', 'prefix', 'options', 'errorBoundary');
    }

    private function inheritGroups(Route $route): void
    {
        $options = [];
        $boundary = null;
        foreach ($this->groups as $group) {
            foreach ($group['options'] as $option) $options[] = $option;
            $boundary = $group['errorBoundary'] ?? $boundary;
        }
        $route->inheritGroup($options, $boundary);
    }

    /** @internal */
    public function popGroup(): void
    {
        array_pop($this->groups);
    }

    /** @internal */
    public function namePrefix(): string
    {
        $prefix = '';
        foreach ($this->groups as $group) $prefix .= $group['name'];

        return $prefix;
    }

    /** @return list<Route> */
    public function all(): array
    {
        return $this->routes;
    }

    /** @return array<string, TabGroup> */
    public function tabGroups(): array
    {
        return $this->tabGroups;
    }

    public function isEmpty(): bool
    {
        return $this->routes === [];
    }

    /** Route by name or by screen class. */
    public function find(string|BackedEnum $route): ?Route
    {
        $key = $route instanceof BackedEnum ? (string) $route->value : $route;
        if (isset($this->named[$key])) return $this->named[$key];
        $class = ltrim($key, '\\');
        foreach ($this->routes as $candidate) {
            if (is_string($candidate->screen) && strcasecmp($candidate->screen, $class) === 0) return $candidate;
        }

        return null;
    }

    public function get(string|BackedEnum $route): Route
    {
        return $this->find($route) ?? throw new InvalidArgumentException(
            'Route '.($route instanceof BackedEnum ? $route->value : $route).' is not registered.',
        );
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function path(string|BackedEnum $route, array $params = []): ?string
    {
        $definition = $this->get($route);
        if ($definition->path === null) return null;

        return (new DeepLink($definition->path, $definition->routeName()))->build($params);
    }

    /** Boot route: ->initial(), else '/', else the first tabs group, else the first route. */
    public function initial(): Route
    {
        if ($this->routes === []) throw new LogicException('routes.php registered no routes.');
        foreach ($this->routes as $route) if ($route->isInitial()) return $route->kind === RouteKind::Tab ? $this->host($route) : $route;
        foreach ($this->routes as $route) if ($route->path === '/' && $route->kind !== RouteKind::Tab) return $route;
        foreach ($this->routes as $route) if ($route->kind === RouteKind::Tabs) return $route;

        return $this->routes[0];
    }

    /** The tabs host of a tab route. */
    public function host(Route $tab): Route
    {
        return $this->named[(string) $tab->tabs] ?? throw new LogicException('Tab route without a host.');
    }

    /** @return list<string|Closure> */
    private function activeMiddleware(): array
    {
        $middleware = [];
        foreach ($this->groups as $group) {
            foreach ($group['middleware'] as $item) $middleware[] = $item;
        }

        return $middleware;
    }

    /** @internal */
    public function prefixed(string $path): string
    {
        $prefix = '';
        foreach ($this->groups as $group) $prefix .= $group['prefix'];
        if ($prefix === '') return $path;

        return $path === '/' ? $prefix : $prefix.'/'.ltrim($path, '/');
    }

    /** '/chat/{id}' => 'chat', '/settings/privacy' => 'settings.privacy', '/' => 'home'. */
    public static function nameFromPath(string $path): string
    {
        $segments = [];
        foreach (explode('/', trim($path, '/')) as $segment) {
            if ($segment === '' || str_starts_with($segment, '{')) continue;
            $segments[] = strtolower((string) preg_replace('/[^A-Za-z0-9-]+/', '-', $segment));
        }
        $name = implode('.', $segments);

        return $name === '' ? 'home' : (ctype_alpha($name[0]) ? $name : 'r'.$name);
    }

    /** @internal Total routes (tests). */
    public function count(): int
    {
        return count($this->routes);
    }
}
