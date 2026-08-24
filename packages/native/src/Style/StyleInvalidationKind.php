<?php

declare(strict_types=1);

namespace Pam\Native\Style;

enum StyleInvalidationKind: int
{
    case Value = 1;
    case State = 2;
    case Container = 3;
    case Viewport = 4;
    case Theme = 5;
    case Environment = 6;
}
