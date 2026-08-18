<?php

declare(strict_types=1);

namespace Pam\Native;

enum ThemeMode: int
{
    case Light = 1;
    case Dark = 2;
    case HighContrast = 3;
}
