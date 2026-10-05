<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

/** Media is ready to play: natural video size in pixels and duration in seconds. */
final readonly class MediaReadyEvent
{
    public function __construct(
        public int $naturalWidth,
        public int $naturalHeight,
        public float $duration,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = $payload === '' ? [] : Wire::decodeMap($payload);

        return new self(
            is_numeric($values['naturalWidth'] ?? null) ? (int) $values['naturalWidth'] : 0,
            is_numeric($values['naturalHeight'] ?? null) ? (int) $values['naturalHeight'] : 0,
            is_numeric($values['duration'] ?? null) ? (float) $values['duration'] : 0.0,
        );
    }
}
