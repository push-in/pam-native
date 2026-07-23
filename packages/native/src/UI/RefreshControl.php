<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class RefreshControl extends Element
{
    public static function make(Renderable $content, bool $refreshing = false): self
    {
        return (new self(NodeKind::RefreshControl))
            ->withChildren([$content])
            ->withProperty(PropKey::Refreshing, $refreshing);
    }

    public function onRefresh(Closure $handler): self
    {
        return $this->withEvent(EventKind::Refresh, $handler);
    }
}
