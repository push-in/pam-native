<?php

declare(strict_types=1);

namespace Pam\Native;

enum ModuleResultStatus: int
{
    case Success = 1;
    case Failure = 2;
}

