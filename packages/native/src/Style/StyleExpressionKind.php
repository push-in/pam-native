<?php

declare(strict_types=1);

namespace Pam\Native\Style;

enum StyleExpressionKind: int
{
    case Literal = 1;
    case Add = 2;
    case Subtract = 3;
    case Multiply = 4;
    case Divide = 5;
    case Minimum = 6;
    case Maximum = 7;
    case Clamp = 8;
    case Environment = 9;
}
