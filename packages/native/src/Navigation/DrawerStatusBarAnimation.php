<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum DrawerStatusBarAnimation: int
{
    case Slide = 1;
    case Fade = 2;
    case None = 3;
}
