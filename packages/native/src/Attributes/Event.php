<?php

declare(strict_types=1);

namespace Pam\Native\Attributes;

use Attribute;

/** Declares a typed event emitted by a component. */
#[Attribute(Attribute::TARGET_CLASS | Attribute::IS_REPEATABLE)]
final readonly class Event
{
    /** @param class-string $payload */
    public function __construct(
        public string $name,
        public string $payload,
    ) {
    }
}
