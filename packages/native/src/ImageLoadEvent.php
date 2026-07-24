<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

final readonly class ImageLoadEvent
{
    public function __construct(
        public string $uri,
        public float $width,
        public float $height,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = Wire::decodeMap($payload);

        return new self(
            uri: is_string($values['uri'] ?? null)
                ? $values['uri']
                : '',
            width: is_numeric($values['width'] ?? null)
                ? (float) $values['width']
                : 0.0,
            height: is_numeric($values['height'] ?? null)
                ? (float) $values['height']
                : 0.0,
        );
    }
}
