<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\ScreenOptionsPatch;

final readonly class RoutePreset
{
    public function __construct(
        public ScreenOptions|ScreenOptionsPatch|Closure $options,
    ) {
    }
}
