<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

/**
 * Emitted once a native drag finished settling onto a snap point
 * (`on:gestureSettle`). [position] is the resting translation in dp.
 */
final readonly class GestureSettleEvent
{
    public function __construct(
        public int $snapIndex,
        public float $position,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = $payload === '' ? [] : Wire::decodeMap($payload);
        $index = $values['snapIndex'] ?? null;
        $position = $values['position'] ?? null;

        return new self(
            is_int($index) ? $index : -1,
            is_int($position) || is_float($position) ? (float) $position : 0.0,
        );
    }

    /** True when the drag rests away from its zero position (e.g. a Swipeable row is open). */
    public function isOpen(): bool
    {
        return abs($this->position) > 0.5;
    }
}
