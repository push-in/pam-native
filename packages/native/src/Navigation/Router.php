<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;

final class Router
{
    /** @var array<string, Closure> */
    private array $routes = [];
    private string $persistenceKey = 'main';
    private NavigationTransition $transition = NavigationTransition::PlatformDefault;
    private int $durationMs = 240;
    private bool $handleSystemBack = true;

    private function __construct(private readonly string $initialRoute)
    {
        if ($initialRoute === '') {
            throw new InvalidArgumentException('The initial route cannot be empty.');
        }
    }

    public static function stack(string $initialRoute): self
    {
        return new self($initialRoute);
    }

    public function route(string $name, Closure $screen): self
    {
        if ($name === '') {
            throw new InvalidArgumentException('Route names cannot be empty.');
        }
        $copy = clone $this;
        $copy->routes[$name] = $screen;

        return $copy;
    }

    public function persistence(string $key): self
    {
        $copy = clone $this;
        $copy->persistenceKey = $key;

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

    public function build(): Navigator
    {
        return new Navigator(
            initialRoute: $this->initialRoute,
            routes: $this->routes,
            persistenceKey: $this->persistenceKey,
            transition: $this->transition,
            transitionDurationMs: $this->durationMs,
            handleSystemBack: $this->handleSystemBack,
        );
    }
}
