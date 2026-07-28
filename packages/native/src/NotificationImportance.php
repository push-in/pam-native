<?php

declare(strict_types=1);

namespace Pam\Native;

enum NotificationImportance: int
{
    case Low = 1;
    case Default = 2;
    case High = 3;
    case Urgent = 4;
}
