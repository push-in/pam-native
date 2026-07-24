<?php

declare(strict_types=1);

namespace Pam\Native\Attributes;

use Attribute;

#[Attribute(Attribute::TARGET_PROPERTY)]
final readonly class State
{
    public function __construct(
        public bool $persist = false,
    ) {
    }
}
