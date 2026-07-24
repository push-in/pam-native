<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\RefreshIndicatorSize;
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

    public function colors(int ...$colors): self
    {
        return $this->withProperty(PropKey::RefreshColors, implode(',', $colors));
    }

    public function progressBackgroundColor(int $color): self
    {
        return $this->withProperty(PropKey::RefreshProgressBackgroundColor, $color);
    }

    public function progressViewOffset(float $offset): self
    {
        return $this->withProperty(PropKey::RefreshProgressViewOffset, $offset);
    }

    public function size(RefreshIndicatorSize $size): self
    {
        return $this->withProperty(PropKey::RefreshIndicatorSize, $size->value);
    }
}
