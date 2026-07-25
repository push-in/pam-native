<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Renderable;

/**
 * A virtualized grid whose cells may contain any PAM component tree.
 */
final class VirtualGrid
{
    public static function make(int $columns, Renderable ...$items): VirtualizedList
    {
        return VirtualizedList::make(...$items)->columns($columns);
    }
}
