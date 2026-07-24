<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\SafeAreaMode;

final class SafeAreaView extends Element
{
    public static function make(Renderable ...$children): self
    {
        return (new self(NodeKind::SafeAreaView))->withChildren($children);
    }

    public function edges(
        bool $top = true,
        bool $right = true,
        bool $bottom = true,
        bool $left = true,
    ): self {
        return $this
            ->withProperty(PropKey::SafeAreaTop, $top)
            ->withProperty(PropKey::SafeAreaRight, $right)
            ->withProperty(PropKey::SafeAreaBottomEdge, $bottom)
            ->withProperty(PropKey::SafeAreaLeft, $left);
    }

    public function mode(SafeAreaMode $mode): self
    {
        return $this->withProperty(PropKey::SafeAreaMode, $mode->value);
    }
}
