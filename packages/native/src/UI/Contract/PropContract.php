<?php

declare(strict_types=1);

namespace Pam\Native\UI\Contract;

final readonly class PropContract
{
    public function __construct(
        public string $name,
        public ValueKind $kind,
        public bool $required = false,
        public bool $bindable = false,
        public ?string $enum = null,
    ) {
    }
}
