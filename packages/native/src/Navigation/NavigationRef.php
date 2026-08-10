<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use BackedEnum;
/**
 * Stable imperative handle for notification handlers, deep-link adapters and
 * code which runs before the navigation tree mounts. Pre-mount actions are
 * bounded and replayed in order once attached.
 */
final class NavigationRef
{
    private ?NavigationContainer $container = null;
    /** @var list<NavigationAction> */
    private array $pending = [];

    public function attach(NavigationContainer $container): void
    {
        $this->container = $container;
        $pending = $this->pending;
        $this->pending = [];
        foreach ($pending as $action) $container->dispatch($action);
    }

    public function detach(NavigationContainer $container): void
    {
        if ($this->container === $container) $this->container = null;
    }

    public function isReady(): bool
    {
        return $this->container?->isReady() === true;
    }

    public function dispatch(NavigationAction $action): bool
    {
        if ($this->container !== null) return $this->container->dispatch($action);
        if (count($this->pending) >= 64) array_shift($this->pending);
        $this->pending[] = $action;
        return true;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function navigate(string|BackedEnum $route, array $params = [], bool $merge = false): bool
    {
        return $this->dispatch(NavigationAction::navigate($route, $params, $merge));
    }

    public function goBack(): bool
    {
        return $this->dispatch(NavigationAction::goBack());
    }

    public function currentRoute(): ?RouteContext
    {
        return $this->container?->getCurrentRoute();
    }

    /** @return array<string, mixed>|null */
    public function rootState(): ?array
    {
        return $this->container?->getRootState();
    }
}
