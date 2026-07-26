<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\HapticFeedback;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\NativeOperation;

final class Haptics
{
    private function __construct()
    {
    }

    public static function trigger(HapticFeedback $feedback): int
    {
        return Runtime::callNative(
            NativeOperation::Haptic,
            Wire::map(['feedback' => $feedback->value]),
            static function (): void {
            },
        );
    }
}
