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
 *
 * Readers re-render only when the font scale changes, not on window resizes.
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
        $scale = $this->scale();

        return match ($name) {
            'value' => $scale,
            'effective' => self::capped($scale, TextScalePolicy::capFor(0.0)),
            default => self::capped($scale, TextScalePolicy::capFor(TextScalePolicy::$displayFontSize)),
        };
    }

    /** The scale text of $fontSize points renders with under the policy. */
    public function for(float $fontSize): float
    {
        return self::capped($this->scale(), TextScalePolicy::capFor($fontSize));
    }

    /**
     * The system scale bounded to [$min, $max] (Flutter `TextScaler.clamp`,
     * React Native `maxFontSizeMultiplier` for derived sizes): use it to grow
     * a fixed row, icon or padding with the text it holds, with a ceiling the
     * layout was designed for.
     */
    public function clamp(float $min = 1.0, float $max = 0.0): float
    {
        $scale = $this->scale();
        if ($max > 0.0) {
            $scale = min($scale, max($min, $max));
        }

        return max($min, $scale);
    }

    /** $size points scaled by the system font scale, bounded by clamp($min, $max). */
    public function scaled(float $size, float $max = 0.0, float $min = 1.0): float
    {
        return $size * $this->clamp($min, $max);
    }

    private function scale(): float
    {
        return $this->source->field('font.scale', static fn (WindowMetrics $m): float => $m->fontScale)->get();
    }

    private static function capped(float $scale, float $cap): float
    {
        return $cap > 0.0 ? min($scale, $cap) : $scale;
    }
}
