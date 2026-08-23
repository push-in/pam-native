<?php

declare(strict_types=1);

namespace Pam\Native\Bridge;

enum NativeCallKind: int
{
    case Request = 1;
    case Event = 2;
    case Stream = 3;
}
