<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Internal\Runtime;
use Pam\Native\NativeOperation;

final class Keyboard
{
    private function __construct()
    {
    }

    public static function dismiss(): int
    {
        return Runtime::callNative(
            NativeOperation::KeyboardDismiss,
            '',
            static function (): void {
            },
        );
    }
}
