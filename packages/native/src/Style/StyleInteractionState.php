<?php

declare(strict_types=1);

namespace Pam\Native\Style;

enum StyleInteractionState: int
{
    case Pressed = 1;
    case Focused = 2;
    case Hovered = 3;
    case Disabled = 4;
    case Checked = 5;
    case Selected = 6;
    case Active = 7;
    case Loading = 8;
    case Error = 9;
}
