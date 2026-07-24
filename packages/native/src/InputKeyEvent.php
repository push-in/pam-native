<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

final readonly class InputKeyEvent
{
    public function __construct(public string $key)
    {
    }

    public static function fromPayload(string $payload): self
    {
        $values = Wire::decodeMap($payload);

        return new self(is_string($values['key'] ?? null) ? $values['key'] : '');
    }
}
