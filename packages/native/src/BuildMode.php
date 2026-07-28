<?php

declare(strict_types=1);

namespace Pam\Native;

enum BuildMode: int
{
    case Development = 1;
    case Production = 2;
    case Benchmark = 3;
}
