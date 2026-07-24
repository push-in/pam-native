<?php

declare(strict_types=1);

namespace Pam\Native;

enum ImageCachePolicy: int
{
    case Default = 1;
    case Reload = 2;
    case ForceCache = 3;
    case OnlyIfCached = 4;
}
