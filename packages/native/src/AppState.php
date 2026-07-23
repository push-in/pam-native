<?php

declare(strict_types=1);

namespace Pam\Native;

enum AppState: int
{
    case Active = 1;
    case Inactive = 2;
    case Background = 3;
}
