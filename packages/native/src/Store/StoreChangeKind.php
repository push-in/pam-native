<?php

declare(strict_types=1);

namespace Pam\Native\Store;

enum StoreChangeKind: int
{
    case Action = 1;
    case Mutation = 2;
    case Undo = 3;
    case Redo = 4;
    case TimeTravel = 5;
    case Hydration = 6;
    case Reset = 7;
    case Rollback = 8;
}
