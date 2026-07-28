<?php

declare(strict_types=1);

namespace Pam\Native;

enum GestureState: int
{
    case Began = 1;
    case Changed = 2;
    case Ended = 3;
    case Cancelled = 4;
    case Failed = 5;
}
