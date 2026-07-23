<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;
use RuntimeException;

final class Alert
{
    private function __construct()
    {
    }

    public static function show(string $title, string $message, ?Closure $dismissed = null): int
    {
        return Runtime::callNative(
            NativeOperation::Alert,
            Wire::map(['title' => $title, 'message' => $message]),
            static function (ModuleResultStatus $status, string $payload) use ($dismissed): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $dismissed?->__invoke();
            },
        );
    }
}
