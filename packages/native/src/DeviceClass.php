<?php

declare(strict_types=1);

namespace Pam\Native;

enum DeviceClass: int
{
    case Compact = 1;
    case Medium = 2;
    case Expanded = 3;
    case Television = 4;
}
