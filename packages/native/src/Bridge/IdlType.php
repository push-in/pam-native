<?php

declare(strict_types=1);

namespace Pam\Native\Bridge;

enum IdlType: int
{
    case String = 1;
    case Integer = 2;
    case Decimal = 3;
    case Boolean = 4;
    case Bytes = 5;
    case StringList = 6;
    case Map = 7;
}
