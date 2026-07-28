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

    public function granted(): bool
    {
        return $this->status === PermissionStatus::Granted
            || $this->status === PermissionStatus::Limited;
    }
}
