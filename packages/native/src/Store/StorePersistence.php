<?php

declare(strict_types=1);

namespace Pam\Native\Store;

interface StorePersistence
{
    /** @return array{version: int, state: array<string, mixed>}|null */
    public function load(string $key): ?array;

    /** @param array<string, mixed> $state */
    public function save(string $key, int $version, array $state): void;

    public function forget(string $key): void;
}
