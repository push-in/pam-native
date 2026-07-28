<?php

declare(strict_types=1);

namespace Pam\Native;

enum GestureComposition: int
{
    case Exclusive = 1;
    case Simultaneous = 2;
    case Race = 3;
}
