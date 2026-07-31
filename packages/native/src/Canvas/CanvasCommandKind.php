<?php

declare(strict_types=1);

namespace Pam\Native\Canvas;

enum CanvasCommandKind: int
{
    case Rectangle = 1;
    case RoundedRectangle = 2;
    case Circle = 3;
    case Line = 4;
}
