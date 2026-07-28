<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class NavigationHost extends Element
{
    public static function make(
        NavigationOperation $operation,
        NavigationTransition $transition,
        int $durationMs,
        int $revision,
        Renderable ...$screens,
    ): self {
        return (new self(NodeKind::NavigationHost))
            ->withChildren($screens)
            ->withProperty(PropKey::FlexGrow, 1.0)
            ->withProperty(PropKey::NavigationOperation, $operation->value)
            ->withProperty(PropKey::NavigationTransition, $transition->value)
            ->withProperty(PropKey::NavigationDurationMs, max(0, min(2_000, $durationMs)))
            ->withProperty(PropKey::NavigationRevision, $revision)
            ->withProperty(PropKey::NavigationGestureEnabled, true)
            ->withProperty(PropKey::NavigationGestureEdgeWidth, 24.0)
            ->withProperty(PropKey::NavigationGestureThreshold, 0.35);
    }

    public function gestureNavigation(
        bool $enabled = true,
        float $edgeWidth = 24.0,
        float $threshold = 0.35,
    ): self {
        return $this
            ->withProperty(PropKey::NavigationGestureEnabled, $enabled)
            ->withProperty(
                PropKey::NavigationGestureEdgeWidth,
                max(8.0, min(96.0, $edgeWidth)),
            )
            ->withProperty(
                PropKey::NavigationGestureThreshold,
                max(0.1, min(0.9, $threshold)),
            );
    }

    public function onGesturePop(Closure $handler): self
    {
        return $this->withEvent(EventKind::NavigationGesturePop, $handler);
    }
}
