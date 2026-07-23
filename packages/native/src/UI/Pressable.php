<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class Pressable extends Element
{
    public static function make(Renderable ...$children): self
    {
        return (new self(NodeKind::Pressable))->withChildren($children);
    }

    public function onPress(Closure $handler): self
    {
        return $this->withEvent(EventKind::Press, $handler);
    }

    public function onLongPress(Closure $handler): self
    {
        return $this->withEvent(EventKind::LongPress, $handler);
    }

    public function ripple(int $color): self
    {
        return $this->withProperty(PropKey::RippleColor, $color);
    }

    public function pressedOpacity(float $opacity): self
    {
        return $this->withProperty(PropKey::PressOpacity, min(1.0, max(0.0, $opacity)));
    }
}
