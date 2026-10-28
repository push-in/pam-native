<?php

declare(strict_types=1);

namespace Pam\Native\Testing;

use BackedEnum;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Renderable;
use Pam\Native\Route;
use Pam\Native\Routing\RouteCollection;
use Pam\Native\Routing\RouteNavigator;
use Pam\Native\Routing\TypedRoute;

/**
 * The app's routes mounted by Testing\routes(): the real RouteNavigator with
 * middleware, typed params and focus, rendered after every step.
 */
final class RouteTester
{
    /** @param Mounted<Renderable> $mounted */
    public function __construct(
        public readonly RouteNavigator $navigator,
        private readonly Mounted $mounted,
    ) {
    }

    /** Opens a path or deep link (`/chat/1`, `myapp://chat/1?unread=2`) like an incoming link. */
    public function open(string $uri): static
    {
        $this->navigator->open($uri);
        $this->mounted->render();

        return $this;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function navigate(string|BackedEnum|TypedRoute $route, array $params = []): static
    {
        $this->navigator->navigate($route, $params);
        $this->mounted->render();

        return $this;
    }

    public function back(): static
    {
        $this->navigator->back();
        $this->mounted->render();

        return $this;
    }

    /** The focused route with its params (`->routeName()`, `->param('id')`). */
    public function current(): ?Route
    {
        return $this->navigator->current();
    }

    public function path(): ?string
    {
        return $this->navigator->currentPath();
    }

    /** The focused screen component (null for closure or plain Renderable screens). */
    public function screen(): ?Component
    {
        return $this->navigator->screen();
    }

    public function render(): Element
    {
        return $this->mounted->render();
    }

    /** Every Text of the focused screen (the whole stack when it is not a Component). */
    public function text(): string
    {
        $rendered = $this->screen()?->__pamRendered();

        return $rendered === null ? $this->mounted->text() : Mounted::textOf($rendered);
    }

    public function unmount(): void
    {
        $this->mounted->unmount();
        if (RouteCollection::active() === $this->navigator) RouteCollection::activate(null);
    }
}
