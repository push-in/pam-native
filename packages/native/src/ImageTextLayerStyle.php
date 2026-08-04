<?php

declare(strict_types=1);

namespace Pam\Native;

enum ImageTextLayerStyle: int
{
    case Plain = 1;
    case Filled = 2;
    case Translucent = 3;
}
