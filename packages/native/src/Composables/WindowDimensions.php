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
 * @property-read DeviceClass $deviceClass width class: compact < 600, medium < 840, expanded (Material)
 * @property-read DeviceClass $heightClass height class: compact < 480, medium < 900, expanded (Material)
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
        return ['width', 'height', 'scale', 'fontScale', 'landscape', 'deviceClass', 'heightClass', 'metrics'];
    }

    /**
     * Each property is its own signal (like Flutter's `MediaQuery.sizeOf`):
     * a component that reads only `width` is not re-rendered when the height
     * (keyboard resize), the insets or the font scale change.
     */
    protected function read(string $name): mixed
    {
        if ($name === 'metrics') {
            return $this->source->signal->get();
        }

        return $this->source->field('window.'.$name, match ($name) {
            'width' => static fn (WindowMetrics $m): float => $m->width,
            'height' => static fn (WindowMetrics $m): float => $m->height,
            'scale' => static fn (WindowMetrics $m): float => $m->density,
            'fontScale' => static fn (WindowMetrics $m): float => $m->fontScale,
            'landscape' => static fn (WindowMetrics $m): bool => $m->width > $m->height,
            'deviceClass' => static fn (WindowMetrics $m): DeviceClass => AdaptiveLayout::classify($m),
            'heightClass' => static fn (WindowMetrics $m): DeviceClass => AdaptiveLayout::classifyHeight($m),
            default => throw new \LogicException("Unknown window property {$name}."),
        })->get();
    }
}
