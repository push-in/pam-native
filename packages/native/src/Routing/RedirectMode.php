<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

enum RedirectMode: int
{
    /** Go back to the route when it is in history, otherwise push it. */
    case Navigate = 1;
    /** Always push a new entry. */
    case Push = 2;
    /** Replace the current entry. */
    case Replace = 3;
    /** Clear the history and start from the route. */
    case Reset = 4;
}
