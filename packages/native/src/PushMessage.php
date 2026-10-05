<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class PushMessage
{
    /** @param array<string, mixed> $data */
    public function __construct(
        public PushEventType $event,
        public string $id,
        public string $title,
        public string $body,
        public array $data,
        public ?string $deepLink,
        /** True when a PushRendering rule already displayed this push natively. */
        public bool $rendered = false,
    ) {
    }
}
