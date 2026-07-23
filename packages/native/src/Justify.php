<?php

declare(strict_types=1);

namespace Pam\Native;

enum Justify: int
{
    case Start = 1;
    case Center = 2;
    case End = 3;
    case SpaceBetween = 4;
    case SpaceAround = 5;
    case SpaceEvenly = 6;
}
