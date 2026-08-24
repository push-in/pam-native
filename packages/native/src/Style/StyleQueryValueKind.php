<?php

declare(strict_types=1);

namespace Pam\Native\Style;

enum StyleQueryValueKind: int
{
    case Number = 1;
    case Keyword = 2;
}
