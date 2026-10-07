<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

use function is_float;
use function is_int;

/**
 * `on:scrollBeginDrag`, `on:scrollEndDrag` and `on:momentumScrollEnd`
 * payload: content offset in dp, release velocity in dp/s (end drag only)
 * and the page index along the scroll axis (paging or snap interval).
 */
final readonly class ScrollPhaseEvent
{
    public function __construct(
        public float $x,
        public float $y,
        public float $velocityX,
        public float $velocityY,
        public int $page,
    ) {
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
        );
    }
}
