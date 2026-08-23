<?php

declare(strict_types=1);

namespace Pam\Native\Attributes;

use Attribute;

/** Declares a named, cardinality-checked component slot. */
#[Attribute(Attribute::TARGET_CLASS | Attribute::IS_REPEATABLE)]
final readonly class Slot
{
    public function __construct(
        public string $name = 'slot',
        public int $minimum = 0,
        public ?int $maximum = null,
    ) {
    }
}
