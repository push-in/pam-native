<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

interface RouteModule
{
    public function register(): void;
}
