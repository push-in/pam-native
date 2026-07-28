<?php

declare(strict_types=1);

namespace Pam\Native;

enum MediaPriority: int
{
    case Background = 1;
    case Prefetch = 2;
    case Normal = 3;
    case Visible = 4;
    case Immediate = 5;
}
