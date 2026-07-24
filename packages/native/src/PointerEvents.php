<?php

declare(strict_types=1);

namespace Pam\Native;

enum PointerEvents: int
{
    case Auto = 1;
    case None = 2;
    case BoxNone = 3;
    case BoxOnly = 4;
}
