<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

interface NavigationLifecycleAware
{
    public function navigationFocused(RouteContext $route): void;

    public function navigationBlurred(RouteContext $route): void;

    public function navigationBeforeRemove(RouteContext $route, NavigationAction $action): bool;

    /** Called once when the instance leaves both stack and keep-alive storage, after its exit transition. */
    public function navigationRemoved(RouteContext $route): void;
}
