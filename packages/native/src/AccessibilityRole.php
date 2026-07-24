<?php

declare(strict_types=1);

namespace Pam\Native;

enum AccessibilityRole: int
{
    case Generic = 1;
    case Button = 2;
    case Input = 3;
    case Image = 4;
    case Switch = 5;
    case Adjustable = 6;
    case Alert = 7;
    case Checkbox = 8;
    case ComboBox = 9;
    case Header = 10;
    case ImageButton = 11;
    case KeyboardKey = 12;
    case Link = 13;
    case Menu = 14;
    case MenuBar = 15;
    case MenuItem = 16;
    case None = 17;
    case ProgressBar = 18;
    case Radio = 19;
    case RadioGroup = 20;
    case ScrollBar = 21;
    case Search = 22;
    case SpinButton = 23;
    case Summary = 24;
    case Tab = 25;
    case TabList = 26;
    case Text = 27;
    case Timer = 28;
    case ToggleButton = 29;
    case Toolbar = 30;
    case Grid = 31;
    case List = 32;
    case ListItem = 33;
    case Presentation = 34;
}
