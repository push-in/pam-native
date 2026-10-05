<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

/**
 * React Native `onLayout` payload: the element's frame in points relative to
 * its parent, after the layout pass. Delivered on mount and whenever the
 * frame changes, coalesced to one event per element per frame.
 */
final readonly class LayoutEvent
{
    public function __construct(
        public float $x,
        public float $y,
        public float $width,
        public float $height,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = $payload === '' ? [] : Wire::decodeMap($payload);
        $number = static fn (string $key): float => is_numeric($values[$key] ?? null) ? (float) $values[$key] : 0.0;

        return new self($number('x'), $number('y'), $number('width'), $number('height'));
    }
}
