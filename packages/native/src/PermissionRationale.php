<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;

/**
 * Explanation shown in a native alert before `Permissions::requestKind()`
 * asks again, when the platform says a rationale should be shown (Android
 * only; iOS never asks for one).
 */
final readonly class PermissionRationale
{
    public function __construct(
        public string $title,
        public string $message,
        public string $confirmLabel,
        public string $cancelLabel,
    ) {
        if (trim($confirmLabel) === '' || trim($cancelLabel) === '') {
            throw new InvalidArgumentException('Permission rationale buttons need labels.');
        }
    }
}
