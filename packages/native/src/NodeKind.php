<?php

declare(strict_types=1);

namespace Pam\Native;

enum NodeKind: int
{
    case Screen = 1;
    case Column = 2;
    case Row = 3;
    case Text = 4;
    case Button = 5;
    case Input = 6;
    case Image = 7;
    case Scroll = 8;
    case List = 9;
    case Spacer = 10;
    case View = 11;
    case Pressable = 12;
    case ActivityIndicator = 13;
    case Switch = 14;
    case Modal = 15;
    case ImageBackground = 16;
    case KeyboardAvoidingView = 17;
    case SectionList = 18;
    case RefreshControl = 19;
    case StatusBar = 20;
    case SafeAreaView = 21;
    case DrawerLayout = 22;
    case InputAccessoryView = 23;
    case CustomView = 24;
    case NavigationHost = 25;
}
