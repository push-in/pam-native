<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

final readonly class ImageProgressEvent
{
    public function __construct(
        public int $loaded,
        public int $total,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = Wire::decodeMap($payload);

        return new self(
            loaded: is_int($values['loaded'] ?? null)
                ? max(0, $values['loaded'])
                : 0,
            total: is_int($values['total'] ?? null)
                ? max(0, $values['total'])
                : 0,
        );
    }
}
