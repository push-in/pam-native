<?php

declare(strict_types=1);

namespace Pam\Native;

enum MediaCacheEntryState: int
{
    case Pending = 1;
    case Ready = 2;
    case Corrupted = 3;
    case Evicting = 4;
}
