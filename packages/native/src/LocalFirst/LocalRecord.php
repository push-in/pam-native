<?php

declare(strict_types=1);

namespace Pam\Native\LocalFirst;

final readonly class LocalRecord
{
    /** @param array<string, mixed> $attributes */
    public function __construct(
        public string $collection,
        public string $id,
        public array $attributes,
        public int $version,
        public int $updatedAtMs,
        public bool $deleted = false,
    ) {
        if (preg_match('/^[a-z][a-z0-9_.-]{0,63}$/D', $collection) !== 1 || $id === '' || $version < 1) {
            throw new \InvalidArgumentException('Local-first record identity or version is invalid.');
        }
    }
}
