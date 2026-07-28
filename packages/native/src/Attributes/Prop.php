<?php

declare(strict_types=1);

namespace Pam\Native\Attributes;

use Attribute;

#[Attribute(Attribute::TARGET_PARAMETER | Attribute::TARGET_PROPERTY)]
final readonly class Prop
{
    public function __construct(
        public bool $required = false,
        public int|float|null $min = null,
        public int|float|null $max = null,
        public ?string $enum = null,
        public bool $immutable = true,
    ) {
    }
}
