<?php

declare(strict_types=1);

namespace Pam\Native;

enum PermissionKind: int
{
    case Camera = 1;
    case Microphone = 2;
    case Photos = 3;
    case Notifications = 4;
    case LocationWhenInUse = 5;
    case Contacts = 6;
}
