<?php

declare(strict_types=1);

namespace Pam\Native;

enum AccessibilityLiveRegion: int
{
    case None = 1;
    case Polite = 2;
    case Assertive = 3;
}
