<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Renderable;

final class TouchableNativeFeedback
{
    private function __construct()
    {
    }

    public static function make(
        int $rippleColor,
        Renderable ...$children,
    ): Pressable {
        return Pressable::make(...$children)->ripple($rippleColor);
    }
}
