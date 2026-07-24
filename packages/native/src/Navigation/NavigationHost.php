<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Pam\Native\Element;
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
            ->withProperty(PropKey::NavigationOperation, $operation->value)
            ->withProperty(PropKey::NavigationTransition, $transition->value)
            ->withProperty(PropKey::NavigationDurationMs, max(0, min(2_000, $durationMs)))
            ->withProperty(PropKey::NavigationRevision, $revision);
    }
}
