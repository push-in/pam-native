<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

/** Which gesture plays a {@see TapEffect}. */
enum TapEffectTrigger: int
{
    /** The second tap of a double tap (needs `on:doubleTap`). */
    case DoubleTap = 1;
    /** Every press (`on:press`), on the UI thread before PHP receives it. */
    case Tap = 2;

    public function token(): string
    {
        return match ($this) {
            self::DoubleTap => 'double',
            self::Tap => 'tap',
        };
    }
}
