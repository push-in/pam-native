<?php

declare(strict_types=1);

namespace Pam\Native;

enum ScrollOverScrollMode: int
{
    case Auto = 1;
    case Always = 2;
    case Never = 3;
}
