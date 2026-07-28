<?php

declare(strict_types=1);

namespace Pam\Native\Store;

final readonly class StoreChange
{
    /** @param array<string, array{before: mixed, after: mixed}> $diff */
    public function __construct(
        public int $id,
        public string $store,
        public string $name,
        public StoreChangeKind $kind,
        public array $diff,
        public float $timestamp,
    ) {
    }
}
