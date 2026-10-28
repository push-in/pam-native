<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use InvalidArgumentException;

use function is_array;

/** Attributes shared by the routes of a group: `Route::middleware('auth')->group(fn () => ...)`. */
final class PendingRouteGroup
{
    /** @var list<string|Closure> */
    private array $middleware = [];
    private string $name = '';
    private string $prefix = '';

    /** @param string|Closure|list<string|Closure> ...$middleware */
    public function middleware(string|Closure|array ...$middleware): self
    {
        $copy = clone $this;
        foreach ($middleware as $entry) {
            foreach (is_array($entry) ? $entry : [$entry] as $item) $copy->middleware[] = $item;
        }

        return $copy;
    }

    /** Prefix for the names of the group's routes, e.g. 'settings.'. */
    public function name(string $prefix): self
    {
        $copy = clone $this;
        $copy->name .= $prefix;

        return $copy;
    }

    /** Path prefix for the group's routes, e.g. '/settings'. */
    public function prefix(string $path): self
    {
        $path = '/'.trim($path, '/');
        if ($path === '/') return $this;
        if (preg_match('#^(/[A-Za-z0-9._~{}-]+)+$#D', $path) !== 1) {
            throw new InvalidArgumentException("Invalid route prefix {$path}.");
        }
        $copy = clone $this;
        $copy->prefix .= $path;

        return $copy;
    }

    public function group(Closure $routes): void
    {
        $collection = RouteCollection::current();
        $collection->pushGroup($this->middleware, $this->name, $this->prefix);
        try {
            $routes();
        } finally {
            $collection->popGroup();
        }
    }

    /** Groups the routes inside as tabs sharing these attributes. */
    public function tabs(Closure $routes, string $name = 'tabs'): TabGroup
    {
        $tabs = null;
        $this->group(static function () use ($routes, $name, &$tabs): void {
            $tabs = RouteCollection::current()->tabs($name, $routes);
        });

        return $tabs;
    }
}
