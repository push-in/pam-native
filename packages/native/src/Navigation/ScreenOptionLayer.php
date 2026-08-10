<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use ReflectionFunction;

/** @internal Shared option-layer resolution for Router and the Route facade. */
final class ScreenOptionLayer
{
    private function __construct()
    {
    }

    public static function apply(
        ScreenOptions|ScreenOptionsPatch|Closure $layer,
        RouteContext $route,
        ScreenOptions $inherited,
    ): ScreenOptions {
        if ($layer instanceof Closure) {
            $reflection = new ReflectionFunction($layer);
            $layer = match ($reflection->getNumberOfParameters()) {
                0 => $layer(),
                1 => $layer($route),
                default => $layer($route, $inherited),
            };
        }
        if ($layer instanceof ScreenOptionsPatch) {
            return $layer->apply($inherited);
        }
        if ($layer instanceof ScreenOptions) {
            return $layer;
        }

        throw new InvalidArgumentException(
            'Screen option resolvers must return ScreenOptions or ScreenOptionsPatch.',
        );
    }
}
