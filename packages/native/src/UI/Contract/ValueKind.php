<?php

declare(strict_types=1);

namespace Pam\Native\UI\Contract;

enum ValueKind: int
{
    case String = 1;
    case Integer = 2;
    case Float = 3;
    case Boolean = 4;
    case Array = 5;
    case Object = 6;
    case Enum = 7;
    case Renderable = 8;
    case Any = 9;
}
