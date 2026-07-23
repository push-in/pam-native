<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\NativeOperation;

final class Toast
{
    private function __construct()
    {
    }

    public static function show(string $message, bool $long = false): int
    {
        return Runtime::callNative(
            NativeOperation::Toast,
            Wire::map(['message' => $message, 'long' => $long]),
            static function (): void {
            },
        );
    }
}
