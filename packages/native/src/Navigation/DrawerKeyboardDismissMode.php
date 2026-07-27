<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum DrawerKeyboardDismissMode: int
{
    case OnDrag = 1;
    case None = 2;
}
