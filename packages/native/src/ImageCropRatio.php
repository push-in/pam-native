<?php

declare(strict_types=1);

namespace Pam\Native;

enum ImageCropRatio: int
{
    case Original = 1;
    case Square = 2;
    case Portrait = 3;
    case Story = 4;
    case Landscape = 5;
}
