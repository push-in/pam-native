<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use InvalidArgumentException;
use Pam\Native\Middleware;
use Pam\Native\Route;

use function explode;
use function is_string;

/**
 * Middleware aliases registered with `App::middleware(['auth' => Authenticate::class])`.
 * A route lists aliases (optionally with arguments: 'role:admin,editor') or
 * closures `fn (Route $to, Closure $next) => ...`.
 */
final class MiddlewareRegistry
{
    /** @var array<string, class-string<Middleware>|Middleware|Closure> */
    private static array $aliases = [];
    /** @var array<class-string<Middleware>, Middleware> */
    private static array $instances = [];

    private function __construct()
    {
    }

    /** @param array<string, class-string<Middleware>|Middleware|Closure> $aliases */
    public static function alias(array $aliases): void
    {
        foreach ($aliases as $alias => $middleware) {
            if (!is_string($alias) || preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/D', $alias) !== 1) {
                throw new InvalidArgumentException('Middleware aliases must be safe identifiers.');
            }
            if (is_string($middleware) && !is_a($middleware, Middleware::class, true)) {
                throw new InvalidArgumentException("Middleware {$alias} must implement ".Middleware::class.'.');
            }
            self::$aliases[$alias] = $middleware;
        }
    }

    /**
     * Loads `config/middleware.php` (a file returning alias => middleware).
     * Aliases already registered with App::middleware() keep their value.
     * Returns false when the file does not exist.
     */
    public static function loadConfig(string $file): bool
    {
        if (!is_file($file)) return false;
        $aliases = (static fn (string $path): mixed => require $path)($file);
        if (!is_array($aliases)) {
            throw new InvalidArgumentException("{$file} must return an array of middleware aliases.");
        }
        self::alias(array_diff_key($aliases, self::$aliases));

        return true;
    }

    public static function has(string $alias): bool
    {
        return isset(self::$aliases[explode(':', $alias, 2)[0]]);
    }

    /** @return array<string, class-string<Middleware>|Middleware|Closure> */
    public static function aliases(): array
    {
        return self::$aliases;
    }

    /** @internal */
    public static function reset(): void
    {
        self::$aliases = [];
        self::$instances = [];
    }

    /**
     * Runs the route's middleware; returns true when every middleware called
     * $next, a Redirect, or false when one stopped the navigation.
     */
    public static function run(Route $to): bool|Redirect
    {
        $pipeline = static fn (Route $route): bool => true;
        foreach (array_reverse($to->middleware()) as $entry) {
            $handler = self::resolve($entry);
            $next = $pipeline;
            $pipeline = static fn (Route $route): mixed => $handler($route, $next);
        }
        $result = $pipeline($to);

        return $result === true || $result instanceof Redirect ? $result : false;
    }

    /** @return Closure(Route, Closure): mixed */
    private static function resolve(string|Closure $entry): Closure
    {
        if ($entry instanceof Closure) return $entry;
        [$alias, $arguments] = explode(':', $entry, 2) + [1 => ''];
        $target = self::$aliases[$alias] ?? (is_a($alias, Middleware::class, true) ? $alias : null);
        if ($target === null) {
            throw new InvalidArgumentException("Middleware {$alias} is not registered; add it with App::middleware().");
        }
        $parameters = $arguments === '' ? [] : explode(',', $arguments);
        if ($target instanceof Closure) {
            return static fn (Route $route, Closure $next): mixed => $target($route, $next, ...$parameters);
        }
        $instance = $target instanceof Middleware ? $target : (self::$instances[$target] ??= new $target());

        return static fn (Route $route, Closure $next): mixed => $instance->handle($route, $next, ...$parameters);
    }
}
