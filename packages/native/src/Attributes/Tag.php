<?php

declare(strict_types=1);

namespace Pam\Native\Attributes;

use Attribute;

/** Gives a Composer component a stable template tag. */
#[Attribute(Attribute::TARGET_CLASS)]
final readonly class Tag
{
    public function __construct(public string $name)
    {
    }
}
