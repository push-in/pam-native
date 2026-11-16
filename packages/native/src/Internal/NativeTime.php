<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use InvalidArgumentException;
use Pam\Native\ClockFormat;

/**
 * Wire values of the native time primitives (clock texts, timed progress,
 * text reveal), shared by the element builders and templates.
 *
 * @internal
 */
final class NativeTime
{
    /** Longest `revealKey`: a restart token, not content. */
    private const int KEY_BYTES = 256;

    private function __construct()
    {
    }

    /**
     * Epoch milliseconds or a duration, `>= 0`; `null`, `false` and `''`
     * remove the property (the primitive turns off).
     */
    public static function milliseconds(mixed $value, string $label): ?int
    {
        if ($value === null || $value === false || $value === '') {
            return null;
        }
        if (is_int($value)) {
            return max(0, $value);
        }
        if (is_float($value) && is_finite($value)) {
            // `microtime(true) * 1000` is a float.
            return max(0, (int) floor($value));
        }
        if (is_string($value) && preg_match('/^-?\\d+(\\.\\d+)?$/', $value) === 1) {
            return max(0, (int) floor((float) $value));
        }

        throw new InvalidArgumentException("{$label} must be a number of milliseconds.");
    }

    /** Like {@see milliseconds()}, but `0` also removes the property. */
    public static function positiveMilliseconds(mixed $value, string $label): ?int
    {
        $ms = self::milliseconds($value, $label);

        return $ms === null || $ms === 0 ? null : $ms;
    }

    public static function format(mixed $value): int
    {
        if ($value instanceof ClockFormat) {
            return $value->value;
        }
        if (is_int($value)) {
            return ClockFormat::from($value)->value;
        }
        if (is_string($value)) {
            return ClockFormat::fromTemplate($value)->value;
        }

        throw new InvalidArgumentException('clockFormat must be a ClockFormat, m:ss, mm:ss, h:mm:ss or seconds.');
    }

    public static function key(mixed $value): ?string
    {
        if ($value === null || $value === false) {
            return null;
        }
        if (!is_string($value) && !is_int($value) && !$value instanceof \Stringable) {
            throw new InvalidArgumentException('revealKey must be a string or an integer.');
        }
        $key = (string) $value;
        if (strlen($key) > self::KEY_BYTES) {
            throw new InvalidArgumentException('revealKey cannot exceed '.self::KEY_BYTES.' bytes.');
        }

        return $key;
    }
}
