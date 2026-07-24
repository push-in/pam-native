<?php

declare(strict_types=1);

namespace Pam\Native;

enum AccessibilityCheckedState: int
{
    case Unchecked = 1;
    case Checked = 2;
    case Mixed = 3;
}
