<?php

declare(strict_types=1);

namespace Pam\Native;

enum MediaPickerType: int
{
    case Image = 1;
    case Video = 2;
    case Audio = 3;
    case Any = 4;
    case Media = 5;
}
