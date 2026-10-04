<?php

declare(strict_types=1);

namespace Pam\Native;

/** The indicator's appearance, not the surrounding surface's theme. */
enum ScrollIndicatorStyle: int
{
    case Auto = 1;
    case Dark = 2;
    case Light = 3;
}
