<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

final readonly class BottomSheetEvent
{
    public function __construct(
        public int $index,
        public float $position,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = Wire::decodeMap($payload);
        $position = $values['position'] ?? 0.0;

        return new self(
            index: is_int($values['index'] ?? null) ? $values['index'] : 0,
            position: is_int($position) || is_float($position)
                ? (float) $position
                : 0.0,
        );
    }
}
