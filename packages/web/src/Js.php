<?php

declare(strict_types=1);

namespace Pam\Native\Web;

/** JS literal helpers. */
final class Js
{
    public static function string(string $value): string
    {
        return json_encode($value, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE);
    }

    public static function number(float $value): string
    {
        if (is_nan($value)) {
            return 'NaN';
        }
        if (is_infinite($value)) {
            return $value > 0 ? 'Infinity' : '-Infinity';
        }
        $code = var_export($value, true);

        return str_contains($code, '.') || str_contains($code, 'E') || str_contains($code, 'e') ? $code : $code.'.0';
    }

    /** JS identifier for a PHP class or function name (`App\Screens\Home` → `App_Screens_Home`). */
    public static function name(string $fqcn): string
    {
        return str_replace('\\', '_', ltrim($fqcn, '\\'));
    }

    public static function value(mixed $value): string
    {
        return match (true) {
            $value === null => 'null',
            is_bool($value) => $value ? 'true' : 'false',
            is_int($value) => (string) $value,
            is_float($value) => self::number($value),
            is_string($value) => self::string($value),
            is_array($value) && array_is_list($value) => '['.implode(', ', array_map(self::value(...), $value)).']',
            is_array($value) => 'PAM.arr(['.implode(', ', array_map(
                static fn (int|string $key, mixed $item): string => '['.self::value($key).', '.self::value($item).']',
                array_keys($value),
                $value,
            )).'])',
            default => throw new \RuntimeException('Cannot write '.get_debug_type($value).' as JS.'),
        };
    }
}
