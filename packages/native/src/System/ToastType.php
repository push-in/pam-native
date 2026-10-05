<?php

declare(strict_types=1);

namespace Pam\Native\System;

/** react-native-toast-message types and their default accent colors. */
enum ToastType: int
{
    case Success = 1;
    case Error = 2;
    case Info = 3;

    public function accentColor(): int
    {
        return match ($this) {
            self::Success => 0xFF69C779,
            self::Error => 0xFFFE6301,
            self::Info => 0xFF87CEFA,
        };
    }
}
