<?php

declare(strict_types=1);

namespace Pam\Native;

final class AdaptiveLayout
{
    private function __construct()
    {
    }

    public static function classify(
        ?WindowMetrics $window = null,
        bool $television = false,
    ): DeviceClass {
        if ($television) {
            return DeviceClass::Television;
        }
        $width = ($window ?? App::windowMetrics())->width;
        return match (true) {
            $width >= 840.0 => DeviceClass::Expanded,
            $width >= 600.0 => DeviceClass::Medium,
            default => DeviceClass::Compact,
        };
    }

    /** @template T @param array<int, T> $variants @return T */
    public static function select(array $variants, ?WindowMetrics $window = null): mixed
    {
        $class = self::classify($window);
        return $variants[$class->value]
            ?? $variants[DeviceClass::Compact->value]
            ?? throw new \InvalidArgumentException('Adaptive variants require a compact fallback.');
    }
}
