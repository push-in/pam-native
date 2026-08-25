<?php

declare(strict_types=1);

namespace Pam\Native\Update;

enum UpdateActivationStatus: int
{
    case Staged = 1;
    case Activated = 2;
    case RolledBack = 3;
}
