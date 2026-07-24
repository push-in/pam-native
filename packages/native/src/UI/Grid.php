<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

/**
 * A rich, responsive grid container.
 *
 * Unlike FlatList, Grid accepts arbitrary PAM elements as children. It uses
 * the retained native layout engine and defaults to the conventional 12
 * columns while remaining configurable for specialized layouts.
 */
final class Grid extends Element
{
    public static function make(Renderable ...$children): self
    {
        return (new self(NodeKind::Row))
            ->withChildren($children)
            ->withProperty(PropKey::GridColumns, 12);
    }

    public function columns(int $columns): self
    {
        return $this->withProperty(
            PropKey::GridColumns,
            min(64, max(1, $columns)),
        );
    }

    public function gutter(float $gutter): self
    {
        return $this
            ->withProperty(PropKey::GridColumnGap, max(0.0, $gutter))
            ->withProperty(PropKey::GridRowGap, max(0.0, $gutter));
    }

    public function gutterX(float $gutter): self
    {
        return $this->withProperty(PropKey::GridColumnGap, max(0.0, $gutter));
    }

    public function gutterY(float $gutter): self
    {
        return $this->withProperty(PropKey::GridRowGap, max(0.0, $gutter));
    }
}
