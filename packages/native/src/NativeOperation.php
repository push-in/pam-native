<?php

declare(strict_types=1);

namespace Pam\Native;

enum NativeOperation: int
{
    case HttpGet = 1;
    case StorageGet = 2;
    case StorageSet = 3;
    case Alert = 4;
    case Toast = 5;
    case Share = 6;
    case OpenUrl = 7;
    case CanOpenUrl = 8;
    case Vibrate = 9;
    case DeviceInfo = 10;
    case KeyboardDismiss = 11;
    case PermissionCheck = 12;
    case PermissionRequest = 13;
}
