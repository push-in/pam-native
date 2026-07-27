<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum DrawerBackBehavior: int
{
    case FirstRoute = 1;
    case InitialRoute = 2;
    case Order = 3;
    case History = 4;
    case FullHistory = 5;
    case None = 6;
}
