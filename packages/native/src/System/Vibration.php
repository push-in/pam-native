<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\NativeOperation;

final class Vibration
{
    private function __construct()
    {
    }

    public static function vibrate(int $milliseconds = 30): int
    {
        return Runtime::callNative(
            NativeOperation::Vibrate,
            Wire::map(['milliseconds' => max(1, min(10_000, $milliseconds))]),
            static function (): void {
            },
        );
    }
}
