<?php

declare(strict_types=1);

namespace Pam\Native;

enum EventKind: int
{
    case Press = 1;
    case Change = 2;
    case Back = 3;
    case ModuleResult = 4;
    case LongPress = 5;
    case Focus = 6;
    case Blur = 7;
    case Submit = 8;
    case Scroll = 9;
    case Refresh = 10;
    case Toggle = 11;
    case EndReached = 12;
    case DrawerOpen = 13;
    case DrawerClose = 14;
    case Native = 15;
    case AppState = 16;
    case Dimensions = 17;
    case MemoryPressure = 18;
}
