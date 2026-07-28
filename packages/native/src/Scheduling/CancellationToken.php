<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

final class CancellationToken
{
    private bool $cancelled = false;

    public function cancel(): void
    {
        $this->cancelled = true;
    }

    public function cancelled(): bool
    {
        return $this->cancelled;
    }

    public function throwIfCancelled(): void
    {
        if ($this->cancelled) {
            throw new TaskCancelledException();
        }
    }
}
