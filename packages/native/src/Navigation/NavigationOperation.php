<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum NavigationOperation: int
{
    case Idle = 1;
    case Push = 2;
    case Pop = 3;
    case Replace = 4;
    case Reset = 5;
}
