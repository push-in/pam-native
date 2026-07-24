<?php

declare(strict_types=1);

namespace Pam\Native;

enum TextEllipsizeMode: int
{
    case Tail = 1;
    case Head = 2;
    case Middle = 3;
    case Clip = 4;
}
