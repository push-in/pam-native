<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Renderable;

final class TouchableHighlight
{
    private function __construct()
    {
    }

    public static function make(Renderable ...$children): Pressable
    {
        return Pressable::make(...$children)->pressedOpacity(0.82);
    }
}
