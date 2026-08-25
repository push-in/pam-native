<?php

declare(strict_types=1);

namespace Pam\Native\Update;

enum UpdateChannel: int
{
    case Stable = 1;
    case Beta = 2;
    case Nightly = 3;
}
