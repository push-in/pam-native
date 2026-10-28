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
 */
final class SafeAreaInsets extends ReactiveObject
{
    /** @param SharedSource<WindowMetrics> $source */
    public function __construct(private readonly SharedSource $source)
    {
    }

    protected function properties(): array
    {
        return ['top', 'right', 'bottom', 'left'];
    }

    protected function read(string $name): mixed
    {
        $metrics = $this->source->signal->get();

        return match ($name) {
            'top' => $metrics->safeAreaTop,
            'right' => $metrics->safeAreaRight,
            'bottom' => $metrics->safeAreaBottom,
            default => $metrics->safeAreaLeft,
        };
    }
}
