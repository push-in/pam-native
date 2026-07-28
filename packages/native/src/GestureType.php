<?php

declare(strict_types=1);

namespace Pam\Native;

enum GestureType: int
{
    case Tap = 1;
    case Pan = 2;
    case Pinch = 3;
    case Rotation = 4;
    case Swipe = 5;
    case LongPress = 6;
}
