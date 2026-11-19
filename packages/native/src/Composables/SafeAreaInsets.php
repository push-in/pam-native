<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Pam\Native\WindowMetrics;

/**
 * `useSafeAreaInsets()`: the system bars, notch and home indicator, in points.
 *
 * @property-read float $top
 * @property-read float $right
 * @property-read float $bottom
 * @property-read float $left
 * @property-read float $cutoutTop The display cutout alone (notch, punch hole), without the bars:
 *     what immersive content (status and navigation bars hidden) still has to avoid.
 * @property-read float $cutoutRight
 * @property-read float $cutoutBottom
 * @property-read float $cutoutLeft
 */
final class SafeAreaInsets extends ReactiveObject
{
    /** @param SharedSource<WindowMetrics> $source */
    public function __construct(private readonly SharedSource $source)
    {
    }

    protected function properties(): array
    {
        return ['top', 'right', 'bottom', 'left', 'cutoutTop', 'cutoutRight', 'cutoutBottom', 'cutoutLeft'];
    }

    /** @return array{top: float, right: float, bottom: float, left: float} the safe-area edges (padding-ready) */
    public function toArray(): array
    {
        return ['top' => $this->top, 'right' => $this->right, 'bottom' => $this->bottom, 'left' => $this->left];
    }

    protected function read(string $name): mixed
    {
        return $this->source->field('insets.'.$name, match ($name) {
            'top' => static fn (WindowMetrics $m): float => $m->safeAreaTop,
            'right' => static fn (WindowMetrics $m): float => $m->safeAreaRight,
            'bottom' => static fn (WindowMetrics $m): float => $m->safeAreaBottom,
            'cutoutTop' => static fn (WindowMetrics $m): float => $m->cutoutTop,
            'cutoutRight' => static fn (WindowMetrics $m): float => $m->cutoutRight,
            'cutoutBottom' => static fn (WindowMetrics $m): float => $m->cutoutBottom,
            'cutoutLeft' => static fn (WindowMetrics $m): float => $m->cutoutLeft,
            default => static fn (WindowMetrics $m): float => $m->safeAreaLeft,
        })->get();
    }
}
