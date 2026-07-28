<?php

declare(strict_types=1);

namespace Pam\Native;

enum BottomSheetKeyboardBehavior: int
{
    case Interactive = 1;
    case Extend = 2;
    case FillParent = 3;
}
