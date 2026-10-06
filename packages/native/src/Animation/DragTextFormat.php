<?php

declare(strict_types=1);

namespace Pam\Native\Animation;

/** Native drag labels use sequential integer values on the wire. */
enum DragTextFormat: int
{
    case Number = 1;
    case Clock = 2;
    case ClockWithTotal = 3;
}
