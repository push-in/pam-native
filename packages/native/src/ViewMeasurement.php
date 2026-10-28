<?php

declare(strict_types=1);

namespace Pam\Native;

/**
 * React Native `measure()` result, in points: `x`/`y` relative to the parent
 * (layout frame), `pageX`/`pageY` relative to the app root view and
 * `windowX`/`windowY` relative to the window (`measureInWindow`). Page and
 * window positions include ancestor transforms and scrolling.
 */
final readonly class ViewMeasurement
{
    public function __construct(
        public float $x,
        public float $y,
        public float $width,
        public float $height,
        public float $pageX,
        public float $pageY,
        public float $windowX,
        public float $windowY,
    ) {
    }

    /** @param array<string, mixed> $values */
    public static function fromValues(array $values): self
    {
        $number = static function (string $key) use ($values): float {
            $value = $values[$key] ?? null;

            return is_numeric($value) && is_finite((float) $value) ? (float) $value : 0.0;
        };

        return new self(
            $number('x'),
            $number('y'),
            max(0.0, $number('width')),
            max(0.0, $number('height')),
            $number('pageX'),
            $number('pageY'),
            $number('windowX'),
            $number('windowY'),
        );
    }

    /** True when the point (window coordinates) lies inside the measured frame. */
    public function containsWindowPoint(float $x, float $y): bool
    {
        return $x >= $this->windowX && $x <= $this->windowX + $this->width
            && $y >= $this->windowY && $y <= $this->windowY + $this->height;
    }
}
