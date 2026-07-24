<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

final readonly class PressEvent
{
    public function __construct(
        public float $x,
        public float $y,
        public float $pageX,
        public float $pageY,
        public int $timestamp,
        public int $pointerId,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = Wire::decodeMap($payload);

        return new self(
            self::number($values['x'] ?? null),
            self::number($values['y'] ?? null),
            self::number($values['pageX'] ?? null),
            self::number($values['pageY'] ?? null),
            is_int($values['timestamp'] ?? null) ? $values['timestamp'] : 0,
            is_int($values['pointerId'] ?? null) ? $values['pointerId'] : 0,
        );
    }

    private static function number(mixed $value): float
    {
        return is_int($value) || is_float($value) ? (float) $value : 0.0;
    }
}
