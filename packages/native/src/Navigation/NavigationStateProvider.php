<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

interface NavigationStateProvider
{
    public function key(): string;

    /** @return array<string, mixed> */
    public function getState(): array;
}
