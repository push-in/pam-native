<?php

declare(strict_types=1);

namespace Pam\Native;

enum AnimationPlayState: int
{
    case Running = 1;
    case Paused = 2;
    case Stopped = 3;
}
