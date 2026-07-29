<?php

declare(strict_types=1);

namespace Pam\Native;

enum ImageFilterType: int
{
    case Original = 1;
    case Mono = 2;
    case Vivid = 3;
    case Warm = 4;
    case Cool = 5;
}
