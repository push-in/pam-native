<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use InvalidArgumentException;
use Pam\Native\Navigation\Router;
use Pam\Native\Navigation\TabNavigator;
use Pam\Native\Renderable;

/** @internal */
final class TabRegistrar
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
            throw new InvalidArgumentException("Tab route {$name} is already registered.");
        }
        $this->tabs[] = compact('name', 'label', 'content', 'icon', 'badge');
    }

    public function build(): TabNavigator
    {
        $router = Router::tabs($this->initial)->persistence($this->name);
        foreach ($this->tabs as $tab) {
            $router = $router->tab(
                $tab['name'],
                $tab['label'],
                $tab['content'],
                $tab['icon'],
                $tab['badge'],
            );
        }
        return $router->build();
    }
}
