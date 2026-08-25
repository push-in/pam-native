<?php

declare(strict_types=1);

namespace Pam\Native\Update;

final readonly class UpdateDecision
{
    public function __construct(
        public UpdateDecisionStatus $status,
        public string $message,
        public ?SignedUpdateManifest $manifest = null,
    ) {
    }

    public function approved(): bool
    {
        return $this->status === UpdateDecisionStatus::Approved;
    }
}
