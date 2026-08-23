<?php

declare(strict_types=1);

namespace Pam\Native\Attributes;

use Attribute;

/** Marks a public component method as callable by template events. */
#[Attribute(Attribute::TARGET_METHOD)]
final readonly class Action
{
}
