<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

trait InteractsWithNavigationLifecycle
{
    public function navigationFocused(RouteContext $route): void
    {
    }

    public function navigationBlurred(RouteContext $route): void
    {
    }

    public function navigationBeforeRemove(RouteContext $route, NavigationAction $action): bool
    {
        return true;
    }

    public function navigationRemoved(RouteContext $route): void
    {
    }
}
