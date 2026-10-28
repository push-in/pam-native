<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use BackedEnum;

/** Returned by middleware (`redirect('login')`) to navigate elsewhere instead. */
final readonly class Redirect
{
    public string|BackedEnum $route;

    /** @param array<string, string|int|float|bool|null> $params */
    public function __construct(
        string|BackedEnum|TypedRoute $route,
        public array $params = [],
        public RedirectMode $mode = RedirectMode::Navigate,
    ) {
        if ($route instanceof TypedRoute) {
            $this->route = $route->name;
            $this->params = [...$route->params, ...$params];
            return;
        }
        $this->route = $route;
    }

    /** @param array<string, string|int|float|bool|null> $params */
    public function with(array $params): self
    {
        return new self($this->route, [...$this->params, ...$params], $this->mode);
    }

    public function replace(): self
    {
        return new self($this->route, $this->params, RedirectMode::Replace);
    }

    public function reset(): self
    {
        return new self($this->route, $this->params, RedirectMode::Reset);
    }

    public function push(): self
    {
        return new self($this->route, $this->params, RedirectMode::Push);
    }
}
