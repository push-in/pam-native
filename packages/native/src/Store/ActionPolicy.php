<?php

declare(strict_types=1);

namespace Pam\Native\Store;

enum ActionPolicy: int
{
    case Every = 1;
    case Latest = 2;
    case Leading = 3;
    case Debounced = 4;
}
