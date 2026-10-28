<?php

declare(strict_types=1);

namespace Pam\Native\Plugin;

use Closure;

/** Process hooks installed by plugin providers; no optional core imports. */
final class RuntimeHooks
{
    /** @var array<string, Closure(): void> */
    private static array $reset = [];

    public static function onReset(string $owner, Closure $callback): void
    {
        self::$reset[$owner] = $callback;
    }

    /** @var array<string, Closure(string, array, string): void> */
    private static array $routeFocused = [];

    public static function onRouteFocused(string $owner, Closure $callback): void
    {
        self::$routeFocused[$owner] = $callback;
    }

    public static function routeFocused(string $name, array $params, ?string $path): void
    {
        foreach (self::$routeFocused as $callback) {
            $callback($name, $params, $path);
        }
    }

    public static function forget(string $owner): void
    {
        unset(self::$reset[$owner], self::$routeFocused[$owner]);
    }

    public static function reset(): void
    {
        foreach (self::$reset as $callback) {
            $callback();
        }
    }
}
