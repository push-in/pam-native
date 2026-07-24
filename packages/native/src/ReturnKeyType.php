<?php

declare(strict_types=1);

namespace Pam\Native;

enum ReturnKeyType: int
{
    case Default = 1;
    case Done = 2;
    case Go = 3;
    case Next = 4;
    case Search = 5;
    case Send = 6;
    case None = 7;
    case Previous = 8;
}
