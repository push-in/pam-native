<?php

declare(strict_types=1);

namespace Pam\Native;

enum AnimationFillMode: int
{
    case None = 1;
    case Forwards = 2;
    case Backwards = 3;
    case Both = 4;
}
