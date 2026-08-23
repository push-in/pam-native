<?php

declare(strict_types=1);

namespace Pam\Native\Bridge\Attributes;

use Attribute;

#[Attribute(Attribute::TARGET_CLASS)]
final readonly class NativeModule
{
    public function __construct(
        public int $id,
        public ?string $name = null,
        public int $version = 1,
    ) {
        if ($id < 1 || $version < 1) {
            throw new \InvalidArgumentException('Native module id and version must be positive integers.');
        }
    }
}
