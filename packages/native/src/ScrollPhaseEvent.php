<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

use function is_float;
use function is_int;

/**
 * `on:scrollBeginDrag`, `on:scrollEndDrag` and `on:momentumScrollEnd`
 * payload: content offset in dp, release velocity in dp/s (end drag only),
 * the page index along the scroll axis (paging or snap interval) and, like
 * RN's `contentSize` / `layoutMeasurement`, the content and viewport sizes
 * in dp (0 when the host did not report them).
 */
final readonly class ScrollPhaseEvent
{
    public function __construct(
        public float $x,
        public float $y,
        public float $velocityX,
        public float $velocityY,
        public int $page,
        public float $contentWidth = 0.0,
        public float $contentHeight = 0.0,
        public float $layoutWidth = 0.0,
        public float $layoutHeight = 0.0,
    ) {
    }

    /**
     * Distance (dp) from the viewport's far edge to the content's end along
     * the vertical axis, or null when the host reported no sizes.
     */
    public function distanceFromEnd(): ?float
    {
        if ($this->contentHeight <= 0.0 || $this->layoutHeight <= 0.0) {
            return null;
        }

        return max(0.0, $this->contentHeight - $this->layoutHeight - $this->y);
    }

    public static function fromPayload(string $payload): self
    {
        $values = $payload === '' ? [] : Wire::decodeMap($payload);
        $number = static fn (mixed $value): float => is_int($value) || is_float($value) ? (float) $value : 0.0;
        $page = $values['page'] ?? null;

        return new self(
            $number($values['x'] ?? null),
            $number($values['y'] ?? null),
            $number($values['velocityX'] ?? null),
            $number($values['velocityY'] ?? null),
            is_int($page) ? $page : 0,
            $number($values['contentWidth'] ?? null),
            $number($values['contentHeight'] ?? null),
            $number($values['layoutWidth'] ?? null),
            $number($values['layoutHeight'] ?? null),
        );
    }
}
