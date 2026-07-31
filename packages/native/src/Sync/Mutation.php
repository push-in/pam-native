<?php

declare(strict_types=1);

namespace Pam\Native\Sync;

final readonly class Mutation
{
    /** @param array<string, string|int|float|bool|null> $payload */
    public function __construct(
        public int $id,
        public string $key,
        public string $operation,
        public array $payload,
        public MutationStatus $status = MutationStatus::Queued,
        public int $attempts = 0,
        public int $availableAtMs = 0,
        public ?string $error = null,
    ) {
    }

    public function withStatus(
        MutationStatus $status,
        ?string $error = null,
        ?int $availableAtMs = null,
    ): self {
        return new self(
            $this->id,
            $this->key,
            $this->operation,
            $this->payload,
            $status,
            $status === MutationStatus::Sending ? $this->attempts + 1 : $this->attempts,
            $availableAtMs ?? $this->availableAtMs,
            $error,
        );
    }
}
