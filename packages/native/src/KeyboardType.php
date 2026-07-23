<?php

declare(strict_types=1);

namespace Pam\Native;

enum KeyboardType: int
{
    case Text = 1;
    case Email = 2;
    case Number = 3;
    case Phone = 4;
    case Decimal = 5;
    case Url = 6;
}
