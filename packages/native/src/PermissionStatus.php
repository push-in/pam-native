<?php

declare(strict_types=1);

namespace Pam\Native;

enum PermissionStatus: int
{
    case Granted = 1;
    case Denied = 2;
    case Blocked = 3;
    case Limited = 4;
}
