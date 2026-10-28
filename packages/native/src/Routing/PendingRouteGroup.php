<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use InvalidArgumentException;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\ScreenOptionsPatch;
use Pam\Native\Route;

use function is_array;

/** Attributes shared by the routes of a group: `Route::middleware('auth')->group(fn () => ...)`. */
final class PendingRouteGroup
{
    /** @var list<string|Closure> */
    private array $middleware = [];
    private string $name = '';
    private string $prefix = '';
    /** @var list<ScreenOptionsPatch|ScreenOptions|Closure> */
    private array $options = [];
    private ?Closure $errorBoundary = null;

    /** @param array<string, mixed> $attributes */
    public static function fromAttributes(array $attributes): self
    {
        $group = new self();
        foreach ($attributes as $key => $value) {
            $group = match ($key) {
                'middleware' => $group->middleware($value),
                'name' => $group->name($value),
                'prefix' => $group->prefix($value),
                'options', 'preset' => $group->options($value),
                'errorBoundary' => $group->errorBoundary($value),
                'transition' => $group->options(ScreenOptionsPatch::from(is_array($value)
                    ? ['animation' => $value[0], 'animationDurationMs' => $value[1]]
                    : ['animation' => $value])),
                'gestures' => $group->options(ScreenOptionsPatch::from(['gestureEnabled' => (bool) $value])),
                'fullScreenGestures' => $group->options(ScreenOptionsPatch::from(['fullScreenGestureEnabled' => (bool) $value])),
                'presentation', 'orientation' => $group->options(self::probe($key, $value)),
                default => throw new InvalidArgumentException("Unknown route group attribute {$key}."),
            };
        }

        return $group;
    }

    /** Screen options every route of the group starts from. */
    public function options(ScreenOptionsPatch|ScreenOptions|RoutePreset|Closure $options): self
    {
        $copy = clone $this;
        $copy->options[] = $options instanceof RoutePreset ? $options->options : $options;

        return $copy;
    }

    /** @param Closure(\Throwable, \Pam\Native\Navigation\RouteContext, Closure(): void): \Pam\Native\Renderable $boundary */
    public function errorBoundary(Closure $boundary): self
    {
        $copy = clone $this;
        $copy->errorBoundary = $boundary;

        return $copy;
    }

    private static function probe(string $key, mixed $value): ScreenOptionsPatch|Closure
    {
        $probe = new Route(RouteKind::Screen, null, null);
        $key === 'presentation' ? $probe->presentation($value) : $probe->orientation($value);

        return $probe->screenOptions()[0];
    }

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
        $collection->pushGroup($this->middleware, $this->name, $this->prefix, $this->options, $this->errorBoundary);
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
