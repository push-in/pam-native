<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

enum RouteKind: int
{
    case Screen = 1;
    case Modal = 2;
    case Tab = 3;
    /** The stack entry that hosts a Route::tabs() group. */
    case Tabs = 4;
}
