<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

final readonly class InputSelectionEvent
{
    public function __construct(
        public int $start,
        public int $end,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = Wire::decodeMap($payload);

        return new self(
            is_int($values['start'] ?? null) ? $values['start'] : 0,
            is_int($values['end'] ?? null) ? $values['end'] : 0,
        );
    }
}
