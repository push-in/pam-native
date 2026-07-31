<?php

declare(strict_types=1);

namespace Pam\Native\Sync;

enum MutationStatus: int
{
    case Queued = 1;
    case Sending = 2;
    case Applied = 3;
    case Retry = 4;
    case Conflict = 5;
    case Failed = 6;
}
