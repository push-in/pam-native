<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use BackedEnum;
use Closure;
use InvalidArgumentException;
use Pam\Native\Routing\RouteName;

final class Router
{
    private readonly string $initialRoute;
    /** @var array<string, Closure> */
    private array $routes = [];
    private string $persistenceKey = 'main';
    private NavigationTransition $transition = NavigationTransition::PlatformDefault;
    private int $durationMs = 240;
    private bool $handleSystemBack = true;
    private bool $restoreState = true;
    /** @var list<DeepLink> */
    private array $deepLinks = [];
    /** @var array<string, ScreenOptions|ScreenOptionsPatch|Closure> */
    private array $options = [];
    private ScreenOptions|Closure|null $defaultOptions = null;
    /** @var list<array{routes: list<string>, options: ScreenOptionsPatch|Closure}> */
    private array $optionGroups = [];
    /** @var array<string, Closure> */
    private array $routeIds = [];
    /** @var array<string, Closure> */
    private array $routeGuards = [];
    private ?string $guardFallback = null;
    /** @var list<string> */
    private array $linkingPrefixes = [];
    private ?Closure $linkFilter = null;

    private function __construct(string|BackedEnum $initialRoute)
    {
        $this->initialRoute = RouteName::value($initialRoute);
        if ($this->initialRoute === '') {
            throw new InvalidArgumentException('The initial route cannot be empty.');
        }
    }

    public static function stack(string|BackedEnum $initialRoute): self
    {
        return new self($initialRoute);
    }

    public static function tabs(string|BackedEnum $initialTab): TabRouter
    {
        return new TabRouter(RouteName::value($initialTab));
    }

    public static function topTabs(string|BackedEnum $initialTab): TopTabRouter
    {
        return new TopTabRouter(RouteName::value($initialTab));
    }

    public static function drawer(string|BackedEnum $initialRoute): DrawerRouter
    {
        return new DrawerRouter(RouteName::value($initialRoute));
    }

    public function route(
        string|BackedEnum $name,
        Closure $screen,
        ScreenOptions|ScreenOptionsPatch|Closure|null $options = null,
        ?Closure $getId = null,
    ): self
    {
        $name = RouteName::value($name);
        if ($name === '') {
            throw new InvalidArgumentException('Route names cannot be empty.');
        }
        $copy = clone $this;
        $copy->routes[$name] = $screen;
        if ($options !== null) $copy->options[$name] = $options;
        if ($getId !== null) $copy->routeIds[$name] = $getId;

        return $copy;
    }

    public function screenOptions(ScreenOptions|Closure $options): self
    {
        $copy = clone $this;
        $copy->defaultOptions = $options;
        return $copy;
    }

    /** @param list<string> $routes */
    public function group(array $routes, ScreenOptionsPatch|Closure $options): self
    {
        $copy = clone $this;
        $copy->optionGroups[] = ['routes' => array_values($routes), 'options' => $options];
        return $copy;
    }

    public function persistence(string $key): self
    {
        $copy = clone $this;
        $copy->persistenceKey = $key;

        return $copy;
    }

    /** @param Closure(RouteContext): bool $guard */
    public function guard(string|BackedEnum $route, Closure $guard): self
    {
        $route = RouteName::value($route);
        $copy = clone $this;
        $copy->routeGuards[$route] = $guard;
        return $copy;
    }

    public function guardFallback(string|BackedEnum $route): self
    {
        $route = RouteName::value($route);
        $copy = clone $this;
        $copy->guardFallback = $route;
        return $copy;
    }

    /**
     * Controls whether a cold runtime restores the previously persisted stack.
     * Disable this for apps that must always boot from their initial route.
     */
    public function restoreState(bool $enabled = true): self
    {
        $copy = clone $this;
        $copy->restoreState = $enabled;

        return $copy;
    }

    public function transitions(
        NavigationTransition $transition,
        int $durationMs = 240,
    ): self {
        $copy = clone $this;
        $copy->transition = $transition;
        $copy->durationMs = max(0, min(2_000, $durationMs));

        return $copy;
    }

    public function systemBack(bool $enabled = true): self
    {
        $copy = clone $this;
        $copy->handleSystemBack = $enabled;

        return $copy;
    }

    public function deepLink(string $pattern, string|BackedEnum $route): self
    {
        $copy = clone $this;
        $copy->deepLinks[] = new DeepLink($pattern, RouteName::value($route));

        return $copy;
    }

    /** @param list<string> $prefixes @param (Closure(string): bool)|null $filter */
    public function linking(array $prefixes, ?Closure $filter = null): self
    {
        $copy = clone $this;
        $copy->linkingPrefixes = array_values($prefixes);
        $copy->linkFilter = $filter;
        return $copy;
    }

    public function build(): Navigator
    {
        return new Navigator(
            initialRoute: $this->initialRoute,
            routes: $this->routes,
            persistenceKey: $this->persistenceKey,
            transition: $this->transition,
            transitionDurationMs: $this->durationMs,
            handleSystemBack: $this->handleSystemBack,
            deepLinks: $this->deepLinks,
            restorePersistedState: $this->restoreState,
            screenOptions: $this->options,
            linkingPrefixes: $this->linkingPrefixes,
            linkFilter: $this->linkFilter,
            routeIds: $this->routeIds,
            routeGuards: $this->routeGuards,
            guardFallback: $this->guardFallback,
            defaultOptions: $this->defaultOptions,
            optionGroups: $this->optionGroups,
        );
    }
}
