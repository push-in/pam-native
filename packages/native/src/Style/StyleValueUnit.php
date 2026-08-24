<?php

declare(strict_types=1);

namespace Pam\Native\Style;

enum StyleValueUnit: int
{
    case Number = 1;
    case Px = 2;
    case Dp = 3;
    case Sp = 4;
    case Pt = 5;
    case Rem = 6;
    case Percent = 7;
    case Vw = 8;
    case Vh = 9;
    case Vmin = 10;
    case Vmax = 11;
}
