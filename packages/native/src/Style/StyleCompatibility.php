<?php

declare(strict_types=1);

namespace Pam\Native\Style;

enum StyleCompatibility: int
{
    case Native = 1;
    case Adapted = 2;
    case AndroidOnly = 3;
    case IosOnly = 4;
    case Fallback = 5;
    case Invalid = 6;
}
