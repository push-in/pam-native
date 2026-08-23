<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

final class BackpressureException extends \OverflowException
{
    public function __construct(int $capacity)
    {
        parent::__construct("Async stream exceeded its bounded capacity of {$capacity}.");
    }
}
