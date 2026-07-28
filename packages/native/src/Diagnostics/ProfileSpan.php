<?php

declare(strict_types=1);

namespace Pam\Native\Diagnostics;

final readonly class ProfileSpan
{
    /** @param array<string, string|int|float|bool> $metadata */
    public function __construct(
        public string $name,
        public float $durationMs,
        public float $timestamp,
        public array $metadata,
    ) {
    }
}
