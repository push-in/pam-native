<?php

declare(strict_types=1);

namespace Pam\Native;

enum InputMode: int
{
    case Text = 1;
    case None = 2;
    case Decimal = 3;
    case Numeric = 4;
    case Tel = 5;
    case Search = 6;
    case Email = 7;
    case Url = 8;
}
