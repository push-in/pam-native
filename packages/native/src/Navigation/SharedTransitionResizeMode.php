<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

enum SharedTransitionResizeMode: int
{
    case Scale = 1;
    case Clip = 2;
    case None = 3;
}
