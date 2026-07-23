<?php

declare(strict_types=1);

namespace Pam\Native;

interface Restorable
{
    public function stateKey(): string;

    /** @param array<string, mixed> $state */
    public function restoreState(array $state): void;

    /** @return array<string, mixed> */
    public function saveState(): array;
}
