<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

use InvalidArgumentException;

/** @internal Shared number/value formatting for the native motion contracts. */
final class Motion
{
    public const array PROPERTIES = [
        'opacity', 'translateX', 'translateY', 'scale', 'scaleX', 'scaleY', 'rotate', 'borderRadius',
    ];

    private function __construct()
    {
    }

    public static function number(float|int $value): string
    {
        if (!is_finite((float) $value)) {
            throw new InvalidArgumentException('Motion values must be finite.');
        }
        $formatted = rtrim(rtrim(number_format((float) $value, 4, '.', ''), '0'), '.');

        return $formatted === '-0' || $formatted === '' ? '0' : $formatted;
    }

    /** A number in the property unit (dp for lengths, degrees for rotate) or "<n>%". */
    public static function value(float|int|string $value): string
    {
        if (is_string($value)) {
            $trimmed = trim($value);
            if (preg_match('/^(-?(?:\d+|\d*\.\d+))%$/D', $trimmed, $match) === 1) {
                return self::number((float) $match[1]).'%';
            }
            if (is_numeric($trimmed)) {
                return self::number((float) $trimmed);
            }
            throw new InvalidArgumentException("Invalid motion value {$value}.");
        }

        return self::number($value);
    }

    public static function property(string $property): string
    {
        $aliases = ['x' => 'translateX', 'y' => 'translateY', 'rotation' => 'rotate', 'radius' => 'borderRadius'];
        $resolved = $aliases[$property] ?? $property;
        if (!in_array($resolved, self::PROPERTIES, true)) {
            throw new InvalidArgumentException(
                "Unsupported animated property {$property}; use ".implode(', ', self::PROPERTIES).'.',
            );
        }

        return $resolved;
    }

    public static function milliseconds(int $value, string $label): int
    {
        if ($value < 0 || $value > 60_000) {
            throw new InvalidArgumentException("{$label} must be between 0 and 60000 milliseconds.");
        }

        return $value;
    }
}
