<?php

declare(strict_types=1);

namespace Pam\Native\Routing;

use Closure;
use Pam\Native\Navigation\NavigationGestureDirection;
use Pam\Native\Navigation\NavigationPresentation;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\ScreenOptionsPatch;

final class PendingRoute
{
    /** @internal */
    public function __construct(private readonly RouteDefinition $definition)
    {
    }

    public function options(ScreenOptions|ScreenOptionsPatch|RoutePreset|Closure $options): self
    {
        $this->definition->options[] = $options instanceof RoutePreset ? $options->options : $options;
        return $this;
    }

    public function preset(RoutePreset $preset): self
    {
        return $this->options($preset);
    }

    public function transition(
        NavigationTransition $animation,
        ?int $durationMs = null,
    ): self {
        $options = ['animation' => $animation];
        if ($durationMs !== null) $options['animationDurationMs'] = $durationMs;

        return $this->options(ScreenOptionsPatch::from($options));
    }

    public function gesture(
        bool $enabled = true,
        NavigationGestureDirection $direction = NavigationGestureDirection::Horizontal,
        bool $fullScreen = false,
    ): self {
        return $this->options(ScreenOptionsPatch::from([
            'gestureEnabled' => $enabled,
            'gestureDirection' => $direction,
            'fullScreenGestureEnabled' => $fullScreen,
        ]));
    }

    public function presentation(NavigationPresentation $presentation): self
    {
        return $this->options(ScreenOptionsPatch::one('presentation', $presentation));
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
        if (!in_array($pattern, $this->definition->deepLinks, true)) {
            $this->definition->deepLinks[] = $pattern;
        }
        return $this;
    }

    /** @param list<float>|null $detents */
    public function sheet(
        ?array $detents = null,
        ?int $initialDetent = null,
        ?bool $grabber = null,
        ?float $cornerRadius = null,
        ?bool $expandsWhenScrolledToEdge = null,
    ): self
    {
        $options = ['presentation' => NavigationPresentation::FormSheet];
        if ($detents !== null) $options['sheetAllowedDetents'] = $detents;
        if ($initialDetent !== null) $options['sheetInitialDetentIndex'] = $initialDetent;
        if ($grabber !== null) $options['sheetGrabberVisible'] = $grabber;
        if ($cornerRadius !== null) $options['sheetCornerRadius'] = $cornerRadius;
        if ($expandsWhenScrolledToEdge !== null) {
            $options['sheetExpandsWhenScrolledToEdge'] = $expandsWhenScrolledToEdge;
        }

        return $this->options(ScreenOptionsPatch::from($options));
    }

    public function fullScreen(): self
    {
        return $this->presentation(NavigationPresentation::FullScreenModal);
    }
}
