<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum NavigationOrientation: int
{
    case PlatformDefault = 1;
    case All = 2;
    case Portrait = 3;
    case PortraitUp = 4;
    case PortraitDown = 5;
    case Landscape = 6;
    case LandscapeLeft = 7;
    case LandscapeRight = 8;
}
