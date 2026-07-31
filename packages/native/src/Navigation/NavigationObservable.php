<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;

interface NavigationObservable
{
    public function addListener(NavigationEventType $type, Closure $listener): NavigationSubscription;
}
