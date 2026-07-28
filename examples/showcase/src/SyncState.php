<?php

declare(strict_types=1);

namespace App;

enum SyncState: int
{
    case Ready = 1;
    case Syncing = 2;
    case Complete = 3;
}
