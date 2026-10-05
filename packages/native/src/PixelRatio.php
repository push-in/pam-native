<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Runtime;

/**
 * React Native `PixelRatio` / `StyleSheet.hairlineWidth` equivalents.
 *
 * Layout is authored in density-independent points and snapped to the
 * physical pixel grid by the renderer; these helpers let applications
 * reason about device pixels explicitly.
 */
final class PixelRatio
{
    private function __construct()
    {
    }

    /** Physical pixels per point (Android `displayMetrics.density`). */
    public static function get(): float
    {
        $density = Runtime::windowMetrics()->density;

        return is_finite($density) && $density > 0.0 ? $density : 1.0;
    }

    /** Physical pixels per point multiplied by the accessibility font scale. */
    public static function getFontScale(): float
    {
        $scale = Runtime::windowMetrics()->fontScale;

        return is_finite($scale) && $scale > 0.0 ? $scale : 1.0;
    }

    public static function getPixelSizeForLayoutSize(float $layoutSize): int
    {
        return (int) round($layoutSize * self::get());
    }

    public static function roundToNearestPixel(float $layoutSize, ?float $density = null): float
    {
        $ratio = $density ?? self::get();

        return round($layoutSize * $ratio) / $ratio;
    }

    /**
     * Width of the thinnest line the platform can draw: exactly
     * `StyleSheet.hairlineWidth`, i.e. `roundToNearestPixel(0.4)` and never
     * zero (one physical pixel on 1x–3.5x displays).
     */
    public static function hairlineWidth(?float $density = null): float
    {
        $ratio = $density ?? self::get();
        $width = self::roundToNearestPixel(0.4, $ratio);

        return $width === 0.0 ? 1.0 / $ratio : $width;
    }
}
