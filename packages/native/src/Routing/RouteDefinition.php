<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\ScreenOptionsPatch;

/** @internal */
final class RouteDefinition
{
    public ScreenOptions|ScreenOptionsPatch|Closure|null $options = null;
    public ?Closure $guard = null;
    public ?Closure $getId = null;
    public ?string $deepLink = null;

    public function __construct(
        public readonly string $name,
        public readonly Closure $factory,
    ) {
    }
}
