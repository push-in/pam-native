<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\ScreenOptionsPatch;

/** @internal */
final class RouteDefinition
{
    /** @var list<ScreenOptions|ScreenOptionsPatch|Closure> */
    public array $groupOptions = [];
    /** @var list<ScreenOptions|ScreenOptionsPatch|Closure> */
    public array $options = [];
    public ?Closure $guard = null;
    public ?Closure $getId = null;
    /** @var list<string> */
    public array $deepLinks = [];

    public function __construct(
        public readonly string $name,
        public readonly Closure $factory,
    ) {
    }
}
