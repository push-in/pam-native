<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use Pam\Native\Navigation\NavigationPresentation;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\ScreenOptionsPatch;

final class PendingRoute
{
    /** @internal */
    public function __construct(private readonly RouteDefinition $definition)
    {
    }

    public function options(ScreenOptions|ScreenOptionsPatch|Closure $options): self
    {
        $this->definition->options = $options;
        return $this;
    }

    public function guard(Closure $guard): self
    {
        $this->definition->guard = $guard;
        return $this;
    }

    public function identifyBy(Closure $resolver): self
    {
        $this->definition->getId = $resolver;
        return $this;
    }

    public function deepLink(string $pattern): self
    {
        $this->definition->deepLink = $pattern;
        return $this;
    }

    public function sheet(): self
    {
        $this->definition->options = ScreenOptionsPatch::one(
            'presentation',
            NavigationPresentation::FormSheet,
        );
        return $this;
    }

    public function fullScreen(): self
    {
        $this->definition->options = ScreenOptionsPatch::one(
            'presentation',
            NavigationPresentation::FullScreenModal,
        );
        return $this;
    }
}
