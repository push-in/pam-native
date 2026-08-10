<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum SharedTransitionEasing: int
{
    case Linear = 1;
    case EaseInOut = 2;
    case Spring = 3;
}
