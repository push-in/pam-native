<?php

declare(strict_types=1);

namespace Pam\Native;

enum AnimationEasing: int
{
    case Linear = 1;
    case EaseIn = 2;
    case EaseOut = 3;
    case EaseInOut = 4;
    case Spring = 5;
}
