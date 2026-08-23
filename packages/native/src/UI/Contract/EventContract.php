<?php

declare(strict_types=1);

namespace Pam\Native\UI\Contract;

final readonly class EventContract
{
    /** @param class-string|null $payload */
    public function __construct(
        public string $name,
        public ?string $payload = null,
    ) {
    }
}
