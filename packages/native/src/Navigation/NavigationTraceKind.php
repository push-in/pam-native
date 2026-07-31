<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum NavigationTraceKind: int
{
    case State = 1;
    case Action = 2;
    case UnhandledAction = 3;
    case TransitionStart = 4;
    case TransitionEnd = 5;
    case Gesture = 6;
}
