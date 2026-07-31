<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum NavigationActionType: int
{
    case Navigate = 1;
    case Reset = 2;
    case GoBack = 3;
    case Push = 4;
    case Pop = 5;
    case Replace = 6;
    case PopTo = 7;
    case PopToTop = 8;
    case SetParams = 9;
    case ReplaceParams = 10;
    case Preload = 11;
}
