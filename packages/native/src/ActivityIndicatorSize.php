<?php

declare(strict_types=1);

namespace Pam\Native;

enum ActivityIndicatorSize: int
{
    case Small = 1;
    case Large = 2;

    public function densityIndependentPixels(): float
    {
        return match ($this) {
            self::Small => 20.0,
            self::Large => 36.0,
        };
    }
}
