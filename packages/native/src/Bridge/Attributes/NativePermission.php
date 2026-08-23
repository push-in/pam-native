<?php

declare(strict_types=1);

namespace Pam\Native\Bridge\Attributes;

use Attribute;

#[Attribute(Attribute::TARGET_METHOD | Attribute::IS_REPEATABLE)]
final readonly class NativePermission
{
    public function __construct(public string $capability)
    {
        if (preg_match('/^[a-z][a-z0-9.-]{0,63}$/D', $capability) !== 1) {
            throw new \InvalidArgumentException('Native capabilities must use portable dotted identifiers.');
        }
    }
}
