<?php

declare(strict_types=1);

namespace Pam\Native\Async;

enum PromiseState: int
{
    case Pending = 1;
    case Fulfilled = 2;
    case Rejected = 3;
    case Cancelled = 4;
}
