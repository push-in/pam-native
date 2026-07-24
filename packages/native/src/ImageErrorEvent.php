<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

final readonly class ImageErrorEvent
{
    public function __construct(public string $message)
    {
    }

    public static function fromPayload(string $payload): self
    {
        $values = Wire::decodeMap($payload);

        return new self(
            is_string($values['error'] ?? null)
                ? $values['error']
                : 'Image request failed.',
        );
    }
}
