<?php

declare(strict_types=1);

namespace Pam\Native;

enum MediaCachePolicy: int
{
    case None = 1;
    case Memory = 2;
    case Disk = 3;
    case MemoryAndDisk = 4;
    case CacheFirst = 5;
    case NetworkFirst = 6;
    case CacheOnly = 7;
    case StaleWhileRevalidate = 8;
}
