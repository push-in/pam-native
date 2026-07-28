<?php

declare(strict_types=1);

namespace Pam\Native\Store;

interface StoreMigration
{
    public function fromVersion(): int;

    /** @param array<string, mixed> $state @return array<string, mixed> */
    public function migrate(array $state): array;
}
