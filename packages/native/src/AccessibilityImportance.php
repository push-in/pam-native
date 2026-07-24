<?php

declare(strict_types=1);

namespace Pam\Native;

enum AccessibilityImportance: int
{
    case Auto = 1;
    case Yes = 2;
    case No = 3;
    case NoHideDescendants = 4;
}
