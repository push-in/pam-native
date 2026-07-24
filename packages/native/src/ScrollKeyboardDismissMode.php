<?php

declare(strict_types=1);

namespace Pam\Native;

enum ScrollKeyboardDismissMode: int
{
    case None = 1;
    case OnDrag = 2;
    case Interactive = 3;
}
