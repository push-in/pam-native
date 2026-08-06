<?php

declare(strict_types=1);

namespace Pam\Native;

enum Align: int
{
    case Start = 1;
    case Center = 2;
    case End = 3;
    case Stretch = 4;
    case Baseline = 5;
}
