<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum NavigationEventType: int
{
    case Ready = 1;
    case State = 2;
    case Focus = 3;
    case Blur = 4;
    case BeforeRemove = 5;
    case Removed = 6;
    case TransitionStart = 7;
    case TransitionEnd = 8;
    case GestureStart = 9;
    case GestureEnd = 10;
    case GestureCancel = 11;
    case ParamsChanged = 12;
    case Action = 13;
    case UnhandledAction = 14;
    case TabPress = 15;
    case TabLongPress = 16;
    case DrawerItemPress = 17;
    case SheetDetentChange = 18;
    case TransitionProgress = 19;
}
