<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class Scroll extends Element
{
    public static function make(Renderable $child): self
    {
        return (new self(NodeKind::Scroll))->withChildren([$child]);
    }

    public function scrollEnabled(bool $enabled): self
    {
        return $this->withProperty(PropKey::ScrollEnabled, $enabled);
    }

    public function showsIndicator(bool $visible): self
    {
        return $this->withProperty(PropKey::ShowsScrollIndicator, $visible);
    }

    public function onScroll(Closure $handler): self
    {
        return $this->withEvent(EventKind::Scroll, $handler);
    }
}
