<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

/** Where a {@see TapEffect} anchor plays. */
enum TapEffectOrigin: int
{
    /** Centred on the touch point (Reels/feed double-tap heart). */
    case Touch = 1;
    /** Centred on the Pressable, whatever point was touched. */
    case Center = 2;
    /** Where the anchor is laid out (an icon popping in place). */
    case Anchor = 3;

    public function token(): string
    {
        return match ($this) {
            self::Touch => 'touch',
            self::Center => 'center',
            self::Anchor => 'anchor',
        };
    }
}
