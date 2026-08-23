<?php

declare(strict_types=1);

namespace Pam\Native\UI\Contract;

final readonly class SlotContract
{
    public function __construct(
        public string $name = 'slot',
        public int $minimum = 0,
        public ?int $maximum = null,
    ) {
    }
}
