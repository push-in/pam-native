<?php

declare(strict_types=1);

namespace Pam\Native\Worklets;

enum WorkletOpcode: int
{
    case Input = 1;
    case Constant = 2;
    case Add = 3;
    case Subtract = 4;
    case Multiply = 5;
    case Divide = 6;
    case Clamp = 7;
    case Interpolate = 8;
}
