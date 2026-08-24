<?php

declare(strict_types=1);

namespace Pam\Native\Style;

enum StyleQueryOperator: int
{
    case Equal = 1;
    case GreaterThanOrEqual = 2;
    case LessThanOrEqual = 3;
    case GreaterThan = 4;
    case LessThan = 5;
}
