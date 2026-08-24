<?php

declare(strict_types=1);

namespace Pam\Native\Style;

enum StyleRenderCost: int
{
    case Composite = 1;
    case Paint = 2;
    case Layout = 3;
}
