<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

interface NavigationActionHandler
{
    public function dispatch(NavigationAction $action): bool;
}
