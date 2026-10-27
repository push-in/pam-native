<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * App-wide Dynamic Type / font scale caps by typographic role, the rule React
 * Native apps usually install once in their theme: body text grows up to
 * `$maximum`, display text (font size from `$displayFontSize`: headers,
 * titles, empty-state headlines) only up to `$display`, so a screen at an
 * accessibility size keeps one consistent hierarchy instead of titles
 * outgrowing everything else.
 *
 * Applies to every Text and Input without its own `allowFontScaling="false"`
 * or `maxFontSizeMultiplier`, which stay authoritative (compact chrome such as
 * badges and tab labels keeps its own smaller cap). Call it at boot, before
 * the first render. 0 disables a cap.
 *
 * ```php
 * TextScalePolicy::set(maximum: 1.6, display: 1.3, displayFontSize: 18);
 * ```
 */
final class TextScalePolicy
{
    /** @internal Read by the tree encoder. */
    public static float $maximum = 0.0;

    /** @internal Read by the tree encoder. */
    public static float $display = 0.0;

    /** @internal Read by the tree encoder. */
    public static float $displayFontSize = 18.0;

    /** @internal True when any cap is set. */
    public static bool $active = false;

    private function __construct()
    {
    }

    public static function set(float $maximum = 0.0, float $display = 0.0, float $displayFontSize = 18.0): void
    {
        self::$maximum = max(0.0, $maximum);
        self::$display = max(0.0, $display);
        self::$displayFontSize = max(1.0, $displayFontSize);
        self::$active = self::$maximum > 0.0 || self::$display > 0.0;
    }

    public static function reset(): void
    {
        self::set();
    }

    /** The cap for text of $fontSize points (0: none). */
    public static function capFor(float $fontSize): float
    {
        $cap = self::$maximum;
        if (self::$display > 0.0 && $fontSize >= self::$displayFontSize) {
            $cap = $cap > 0.0 ? min($cap, self::$display) : self::$display;
        }

        return $cap > 0.0 ? max(1.0, $cap) : 0.0;
    }
}
