<?php

declare(strict_types=1);

namespace Pam\Native;

enum AsyncStatus: int
{
    case Loading = 1;
    case Content = 2;
    case Empty = 3;
    case Error = 4;
    case Offline = 5;
    case Stale = 6;
}
