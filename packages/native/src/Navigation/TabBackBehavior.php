<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum TabBackBehavior: int
{
    case None = 1;
    case FirstRoute = 2;
    case InitialRoute = 3;
    case Order = 4;
    case History = 5;
    case FullHistory = 6;
}
