<?php

declare(strict_types=1);

namespace Pam\Native;

enum AccessibilityRole: int
{
    case Generic = 1;
    case Button = 2;
    case Input = 3;
    case Image = 4;
    case Switch = 5;
}
