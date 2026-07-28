<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

final readonly class ScheduledTask
{
    public function __construct(
        public int $id,
        public CancellationToken $token,
    ) {
    }

    public function cancel(): void
    {
        $this->token->cancel();
    }
}
