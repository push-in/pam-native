<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

interface NavigationLifecycleAware
{
    public function navigationFocused(RouteContext $route): void;

    public function navigationBlurred(RouteContext $route): void;

    public function navigationBeforeRemove(RouteContext $route, NavigationAction $action): bool;

    public function navigationRemoved(RouteContext $route): void;
}
