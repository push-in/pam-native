<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use InvalidArgumentException;
use Pam\Native\Navigation\DrawerNavigator;
use Pam\Native\Navigation\DrawerRouter;
use Pam\Native\Navigation\Router;
use Pam\Native\Renderable;

/** @internal */
final class DrawerRegistrar
{
    /** @var list<array{name: string, label: string, content: Renderable|Closure, icon: Renderable|null, badge: string|null, group: string|null}> */
    private array $routes = [];

    public function __construct(
        private readonly string $name,
        private readonly string $initial,
    ) {
    }

    public function add(
        string $name,
        string $label,
        Renderable|Closure $content,
        ?Renderable $icon,
        ?string $badge,
        ?string $group,
    ): void {
        if (array_any($this->routes, static fn (array $route): bool => $route['name'] === $name)) {
            throw new InvalidArgumentException("Drawer route {$name} is already registered.");
        }
        $this->routes[] = compact('name', 'label', 'content', 'icon', 'badge', 'group');
    }

    /** @param null|Closure(DrawerRouter): DrawerRouter $configure */
    public function build(?Closure $configure = null): DrawerNavigator
    {
        $router = Router::drawer($this->initial)->persistence($this->name);
        foreach ($this->routes as $route) {
            $router = $router->route(
                $route['name'],
                $route['label'],
                $route['content'],
                $route['icon'],
                $route['badge'],
                $route['group'],
            );
        }
        if ($configure !== null) {
            $router = $configure($router);
            if (!$router instanceof DrawerRouter) {
                throw new InvalidArgumentException('The drawer configurator must return a DrawerRouter.');
            }
        }
        return $router->build();
    }
}
