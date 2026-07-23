<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

final readonly class BinaryValue
{
    public function __construct(public string $bytes)
    {
    }
}

