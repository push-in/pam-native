<?php

declare(strict_types=1);

namespace Pam\Native\Worklets;

enum WorkletTarget: int
{
    case Opacity = 1;
    case TranslationX = 2;
    case TranslationY = 3;
    case Scale = 4;
    case RotationDegrees = 5;
}
