<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use InvalidArgumentException;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\Router;

/** @internal */
final class RouteRegistrar
{
    /** @var array<string, RouteDefinition> */
    private array $routes = [];
    private NavigationTransition $transition = NavigationTransition::PlatformDefault;
    private int $durationMs = 240;
    private bool $restoreState = true;

    public function __construct(
        private readonly string $name,
        private readonly string $initial,
    ) {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/', $name) !== 1) {
            throw new InvalidArgumentException('Route stack names must be safe identifiers.');
        }
    }

    public function add(string $name, Closure $factory): PendingRoute
    {
        if (isset($this->routes[$name])) {
            throw new InvalidArgumentException("Route {$name} is already registered.");
        }
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/', $name) !== 1) {
            throw new InvalidArgumentException('Route names must be safe identifiers.');
        }

        $definition = new RouteDefinition($name, $factory);
        $this->routes[$name] = $definition;
        return new PendingRoute($definition);
    }

    public function transitions(NavigationTransition $transition, int $durationMs): void
    {
        $this->transition = $transition;
        $this->durationMs = $durationMs;
    }

    public function restoreState(bool $enabled): void
    {
        $this->restoreState = $enabled;
    }

    public function build(): Navigator
    {
        $router = Router::stack($this->initial)
            ->persistence($this->name)
            ->restoreState($this->restoreState)
            ->transitions($this->transition, $this->durationMs);

        foreach ($this->routes as $route) {
            $router = $router->route($route->name, $route->factory, $route->options, $route->getId);
            if ($route->guard !== null) $router = $router->guard($route->name, $route->guard);
            if ($route->deepLink !== null) $router = $router->deepLink($route->deepLink, $route->name);
        }

        return $router->build();
    }
}
