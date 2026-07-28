<?php

declare(strict_types=1);

namespace Pam\Native\Store\Attributes;

use Attribute;

#[Attribute(Attribute::TARGET_METHOD)]
final readonly class Computed
{
}
