<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use InvalidArgumentException;
use Pam\Native\Navigation\Router;
use Pam\Native\Navigation\TopTabNavigator;
use Pam\Native\Navigation\TopTabRouter;
use Pam\Native\Renderable;

/** @internal */
final class TopTabRegistrar
{
    /** @var list<array{name: string, label: string, content: Renderable|Closure, icon: Renderable|null, badge: string|null}> */
    private array $tabs = [];

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
    ): void {
        if (array_any($this->tabs, static fn (array $tab): bool => $tab['name'] === $name)) {
            throw new InvalidArgumentException("Top tab route {$name} is already registered.");
        }
        $this->tabs[] = compact('name', 'label', 'content', 'icon', 'badge');
    }

    /** @param null|Closure(TopTabRouter): TopTabRouter $configure */
    public function build(?Closure $configure = null): TopTabNavigator
    {
        $router = Router::topTabs($this->initial)->persistence($this->name);
        foreach ($this->tabs as $tab) {
            $router = $router->tab(
                $tab['name'],
                $tab['label'],
                $tab['content'],
                $tab['icon'],
                $tab['badge'],
            );
        }
        if ($configure !== null) {
            $router = $configure($router);
            if (!$router instanceof TopTabRouter) {
                throw new InvalidArgumentException('The top-tabs configurator must return a TopTabRouter.');
            }
        }
        return $router->build();
    }
}
