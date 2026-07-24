<?php

declare(strict_types=1);

namespace Pam\Native;

enum TextDataDetectorType: int
{
    case None = 1;
    case PhoneNumber = 2;
    case Link = 3;
    case Email = 4;
    case All = 5;
}
