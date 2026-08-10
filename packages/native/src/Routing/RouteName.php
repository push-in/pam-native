<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use BackedEnum;
use InvalidArgumentException;

/** @internal */
final class RouteName
{
    private function __construct()
    {
    }

    public static function value(string|BackedEnum $name): string
    {
        $value = $name instanceof BackedEnum ? $name->value : $name;
        if (!is_string($value) || preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/', $value) !== 1) {
            throw new InvalidArgumentException('Route names must be safe strings or string-backed enum cases.');
        }

        return $value;
    }
}
