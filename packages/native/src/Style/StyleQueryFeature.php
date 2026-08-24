<?php

declare(strict_types=1);

namespace Pam\Native\Style;

enum StyleQueryFeature: int
{
    case Width = 1;
    case Height = 2;
    case Orientation = 3;
    case ColorScheme = 4;
    case ReducedMotion = 5;
    case Pointer = 6;
    case DeviceType = 7;
    case RefreshRate = 8;
    case DynamicRange = 9;
    case DisplayMode = 10;
    case FoldPosture = 11;
    case InputMode = 12;
    case MemoryClass = 13;
    case PerformanceTier = 14;
}
