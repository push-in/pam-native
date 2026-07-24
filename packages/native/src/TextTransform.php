<?php

declare(strict_types=1);

namespace Pam\Native;

enum TextTransform: int
{
    case None = 1;
    case Uppercase = 2;
    case Lowercase = 3;
    case Capitalize = 4;
}
