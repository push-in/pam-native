<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class DrawerLayoutAndroid extends Element
{
    public static function make(Renderable $content, Renderable $drawer): self
    {
        return (new self(NodeKind::DrawerLayout))->withChildren([$content, $drawer]);
    }

    public function open(bool $open = true): self
    {
        return $this->withProperty(PropKey::DrawerOpen, $open);
    }

    public function onOpen(Closure $handler): self
    {
        return $this->withEvent(EventKind::DrawerOpen, $handler);
    }

    public function onClose(Closure $handler): self
    {
        return $this->withEvent(EventKind::DrawerClose, $handler);
    }
}
