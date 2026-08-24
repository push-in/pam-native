<?php

declare(strict_types=1);

namespace Pam\Native\Style;

use Pam\Native\Internal\Runtime;
use RuntimeException;

/** Application-level reactive CSS custom-property overrides. */
final class StyleVariables
{
    /** @var array<string,string> */
    private static array $values = [];
    private static int $revision = 0;

    private function __construct()
    {
    }

    public static function set(string $name, string|int|float $value): void
    {
        $name = self::name($name);
        $value = (string) $value;
        if ((self::$values[$name] ?? null) === $value) return;
        self::$values[$name] = $value;
        self::$revision++;
        Runtime::requestRender();
    }

    /** @param array<string,string|int|float> $values */
    public static function replace(array $values): void
    {
        $next = [];
        foreach ($values as $name => $value) $next[self::name($name)] = (string) $value;
        ksort($next, SORT_STRING);
        if ($next === self::$values) return;
        self::$values = $next;
        self::$revision++;
        Runtime::requestRender();
    }

    public static function remove(string $name): void
    {
        $name = self::name($name);
        if (!array_key_exists($name, self::$values)) return;
        unset(self::$values[$name]);
        self::$revision++;
        Runtime::requestRender();
    }

    /** @return array<string,string> */
    public static function all(): array { return self::$values; }
    public static function revision(): int { return self::$revision; }

    private static function name(string $name): string
    {
        $name = str_starts_with($name, '--') ? $name : '--'.$name;
        if (preg_match('/^--[A-Za-z_][A-Za-z0-9_-]{0,127}$/D', $name) !== 1) {
            throw new RuntimeException("Invalid reactive CSS variable {$name}.");
        }
        return $name;
    }
}
