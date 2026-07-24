<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum NavigationTransition: int
{
    case PlatformDefault = 1;
    case SlideFromRight = 2;
    case SlideFromLeft = 3;
    case SlideFromBottom = 4;
    case Fade = 5;
    case FadeFromBottom = 6;
    case Scale = 7;
    case None = 8;
}
