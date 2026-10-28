<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Pam\Native\Route;

/**
 * A route name plus validated params, as returned by the generated
 * `Routes::chat(id: $id)` helpers. Pass it to navigate()/redirect() or call
 * its methods directly.
 */
final readonly class TypedRoute
{
    /** @param array<string, string|int|float|bool|null> $params */
    public function __construct(
        public string $name,
        public array $params = [],
    ) {
    }

    public function navigate(): bool
    {
        return Route::navigate($this->name, $this->params);
    }

    public function push(): bool
    {
        return Route::navigate($this->name, $this->params, RedirectMode::Push);
    }

    public function replace(): bool
    {
        return Route::navigate($this->name, $this->params, RedirectMode::Replace);
    }

    public function reset(): bool
    {
        return Route::navigate($this->name, $this->params, RedirectMode::Reset);
    }

    public function redirect(): Redirect
    {
        return new Redirect($this->name, $this->params);
    }

    /** Path of the route with these params (query string for non-path params). */
    public function path(): ?string
    {
        return Route::path($this->name, $this->params);
    }
}
