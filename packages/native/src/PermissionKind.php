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
    /** Android 12+ BLUETOOTH_CONNECT (headsets during calls). iOS: Bluetooth access. */
    case BluetoothConnect = 7;
    /** Android 14+ special access for full-screen call/alarm notifications. */
    case FullScreenIntent = 8;
    /** Android READ_PHONE_STATE (detect cellular calls). Unavailable on iOS. */
    case PhoneState = 9;
}
