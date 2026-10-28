<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Pam\Native\AdaptiveLayout;
use Pam\Native\DeviceClass;
use Pam\Native\WindowMetrics;

/**
 * `useWindowDimensions()`: the app window, like React Native's hook.
 *
 * @property-read float $width
 * @property-read float $height
 * @property-read float $scale pixel density
 * @property-read float $fontScale
 * @property-read bool $landscape
 * @property-read DeviceClass $deviceClass compact, medium or expanded
 * @property-read WindowMetrics $metrics every metric the host reported
 */
final class WindowDimensions extends ReactiveObject
{
    /** @param SharedSource<WindowMetrics> $source */
    public function __construct(private readonly SharedSource $source)
    {
    }

    protected function properties(): array
    {
        return ['width', 'height', 'scale', 'fontScale', 'landscape', 'deviceClass', 'metrics'];
    }

    protected function read(string $name): mixed
    {
        $metrics = $this->source->signal->get();

        return match ($name) {
            'width' => $metrics->width,
            'height' => $metrics->height,
            'scale' => $metrics->density,
            'fontScale' => $metrics->fontScale,
            'landscape' => $metrics->width > $metrics->height,
            'deviceClass' => AdaptiveLayout::classify($metrics),
            default => $metrics,
        };
    }
}
