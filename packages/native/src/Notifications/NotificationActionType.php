<?php

declare(strict_types=1);

namespace Pam\Native\Notifications;

enum NotificationActionType: int
{
    case Reply = 1;
    case MarkRead = 2;
}
