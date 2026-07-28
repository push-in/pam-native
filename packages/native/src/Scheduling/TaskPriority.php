<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

enum TaskPriority: int
{
    case Immediate = 1;
    case UserBlocking = 2;
    case Render = 3;
    case Normal = 4;
    case Background = 5;
    case Idle = 6;
}
