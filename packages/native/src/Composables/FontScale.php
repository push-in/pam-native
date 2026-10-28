<?php

declare(strict_types=1);

namespace Pam\Native\Composables;

use Pam\Native\TextScalePolicy;
use Pam\Native\WindowMetrics;

/**
 * `useFontScale()`: the system font scale and the app's TextScalePolicy caps.
 *
 * @property-read float $value the system scale (1.0 = default)
 * @property-read float $effective body text scale after TextScalePolicy::$maximum
 * @property-read float $display display text scale after TextScalePolicy::$display
 */
final class FontScale extends ReactiveObject
{
    /** @param SharedSource<WindowMetrics> $source */
    public function __construct(private readonly SharedSource $source)
    {
    }

    protected function properties(): array
    {
        return ['value', 'effective', 'display'];
    }

    protected function read(string $name): mixed
    {
        $scale = $this->source->signal->get()->fontScale;

        return match ($name) {
            'value' => $scale,
            'effective' => self::capped($scale, TextScalePolicy::capFor(0.0)),
            default => self::capped($scale, TextScalePolicy::capFor(TextScalePolicy::$displayFontSize)),
        };
    }

    /** The scale text of $fontSize points renders with under the policy. */
    public function for(float $fontSize): float
    {
        return self::capped($this->source->signal->get()->fontScale, TextScalePolicy::capFor($fontSize));
    }

    private static function capped(float $scale, float $cap): float
    {
        return $cap > 0.0 ? min($scale, $cap) : $scale;
    }
}
