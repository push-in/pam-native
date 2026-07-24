<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

final readonly class InputContentSizeEvent
{
    public function __construct(
        public float $width,
        public float $height,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = Wire::decodeMap($payload);

        return new self(
            is_int($values['width'] ?? null) || is_float($values['width'] ?? null)
                ? (float) $values['width']
                : 0.0,
            is_int($values['height'] ?? null) || is_float($values['height'] ?? null)
                ? (float) $values['height']
                : 0.0,
        );
    }
}
