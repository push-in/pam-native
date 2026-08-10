<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use InvalidArgumentException;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\Router;
use Pam\Native\Navigation\ScreenOptionLayer;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\ScreenOptionsPatch;

/** @internal */
final class RouteRegistrar
{
    /** @var array<string, RouteDefinition> */
    private array $routes = [];
    private NavigationTransition $transition = NavigationTransition::PlatformDefault;
    private int $durationMs = 240;
    private bool $restoreState = true;
    private ScreenOptions|Closure|null $defaultOptions = null;
    /** @var list<ScreenOptionsPatch|Closure> */
    private array $activeGroups = [];

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
        $definition->groupOptions = $this->activeGroups;
        $this->routes[$name] = $definition;
        return new PendingRoute($definition);
    }

    public function defaultOptions(ScreenOptions|Closure|null $options): void
    {
        $this->defaultOptions = $options;
    }

    public function beginGroup(ScreenOptionsPatch|Closure $options): void
    {
        $this->activeGroups[] = $options;
    }

    public function endGroup(): void
    {
        array_pop($this->activeGroups);
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

        if ($this->defaultOptions !== null) {
            $router = $router->screenOptions($this->defaultOptions);
        }

        foreach ($this->routes as $route) {
            foreach ($route->groupOptions as $groupOptions) {
                $router = $router->group([$route->name], $groupOptions);
            }
            $options = self::composeOptions($route->options);
            $router = $router->route($route->name, $route->factory, $options, $route->getId);
            if ($route->guard !== null) $router = $router->guard($route->name, $route->guard);
            foreach ($route->deepLinks as $pattern) {
                $router = $router->deepLink($pattern, $route->name);
            }
        }

        return $router->build();
    }

    /**
     * @param list<ScreenOptions|ScreenOptionsPatch|Closure> $layers
     * @return ScreenOptions|ScreenOptionsPatch|Closure|null
     */
    private static function composeOptions(array $layers): ScreenOptions|ScreenOptionsPatch|Closure|null
    {
        if ($layers === []) return null;
        if (count($layers) === 1) return $layers[0];

        return static function ($route, ScreenOptions $inherited) use ($layers): ScreenOptions {
            $resolved = $inherited;
            foreach ($layers as $layer) {
                $resolved = ScreenOptionLayer::apply($layer, $route, $resolved);
            }

            return $resolved;
        };
    }
}
