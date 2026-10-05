<?php

declare(strict_types=1);

namespace Pam\Native\System;

/** Cancellable timer returned by Timers::timeout() and Timers::every(). */
final readonly class TimerHandle
{
    public function __construct(public int $id)
    {
    }

    public function cancel(): void
    {
        Timers::cancel($this);
    }

    public function active(): bool
    {
        return Timers::active($this->id);
    }
}
