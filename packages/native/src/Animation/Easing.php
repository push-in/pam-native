<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

use InvalidArgumentException;

/**
 * Timing curves evaluated natively on the UI thread. The quad/cubic curves
 * match Reanimated's `Easing.in/out/inOut(Easing.quad|cubic)`.
 */
enum Easing: string
{
    case Linear = 'linear';
    case Ease = 'ease';
    case EaseIn = 'ease-in';
    case EaseOut = 'ease-out';
    case EaseInOut = 'ease-in-out';
    case EaseInQuad = 'ease-in-quad';
    case EaseOutQuad = 'ease-out-quad';
    case EaseInOutQuad = 'ease-in-out-quad';
    case EaseInCubic = 'ease-in-cubic';
    case EaseOutCubic = 'ease-out-cubic';
    case EaseInOutCubic = 'ease-in-out-cubic';
    case EaseOutBack = 'ease-out-back';

    /** CSS `cubic-bezier(x1, y1, x2, y2)` encoded for the native runtime. */
    public static function bezier(float $x1, float $y1, float $x2, float $y2): string
    {
        foreach ([$x1, $y1, $x2, $y2] as $value) {
            if (!is_finite($value)) {
                throw new InvalidArgumentException('Bezier control points must be finite.');
            }
        }
        if ($x1 < 0 || $x1 > 1 || $x2 < 0 || $x2 > 1) {
            throw new InvalidArgumentException('Bezier x control points must be between zero and one.');
        }

        return 'bezier:'.implode(':', array_map(Motion::number(...), [$x1, $y1, $x2, $y2]));
    }

    /** Accepts an Easing, a native token, or a CSS timing-function string. */
    public static function token(self|string $easing): string
    {
        if ($easing instanceof self) {
            return $easing->value;
        }
        $value = strtolower(trim($easing));
        if (self::tryFrom($value) !== null || str_starts_with($value, 'bezier:')) {
            return $value;
        }
        if (preg_match('/^cubic-bezier\(\s*([^,]+),\s*([^,]+),\s*([^,]+),\s*([^)]+)\)$/D', $value, $match) === 1) {
            return self::bezier((float) $match[1], (float) $match[2], (float) $match[3], (float) $match[4]);
        }
        throw new InvalidArgumentException("Unsupported easing {$easing}.");
    }
}
