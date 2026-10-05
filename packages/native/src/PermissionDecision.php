<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class PermissionDecision
{
    public function __construct(
        public PermissionKind $kind,
        public PermissionStatus $status,
        public bool $canAskAgain,
    ) {
    }

    public function unavailable(): bool
    {
        return $this->status === PermissionStatus::Unavailable;
    }

    public function granted(): bool
    {
        return $this->status === PermissionStatus::Granted
            || $this->status === PermissionStatus::Limited;
    }
}
