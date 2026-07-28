<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

final readonly class MediaCacheEvent
{
    public function __construct(
        public string $key,
        public int $loaded,
        public int $total,
        public bool $disk,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = Wire::decodeMap($payload);

        return new self(
            key: (string) ($values['key'] ?? ''),
            loaded: (int) ($values['loaded'] ?? 0),
            total: (int) ($values['total'] ?? 0),
            disk: (bool) ($values['disk'] ?? false),
        );
    }
}
