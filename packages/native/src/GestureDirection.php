<?php

declare(strict_types=1);

namespace Pam\Native;

enum GestureDirection: int
{
    case Any = 1;
    case Left = 2;
    case Right = 3;
    case Up = 4;
    case Down = 5;
    case Horizontal = 6;
    case Vertical = 7;
}
