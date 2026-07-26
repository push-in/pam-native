<?php

declare(strict_types=1);

namespace Pam\Native;

enum HapticFeedback: int
{
    case Selection = 1;
    case Light = 2;
    case Medium = 3;
    case Heavy = 4;
    case Success = 5;
    case Warning = 6;
    case Error = 7;
}
