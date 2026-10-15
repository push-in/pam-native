<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
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

    /**
     * A data-driven grid: `$cell($item, $index)` is called only for the cells
     * that are shown (see {@see VirtualizedList::of()}).
     *
     * @param iterable<mixed> $items
     * @param Closure(mixed, int): (Renderable|false|null) $cell
     * @param (Closure(mixed, int): (string|int))|null $key
     */
    public static function of(int $columns, iterable $items, Closure $cell, ?Closure $key = null): VirtualizedList
    {
        return VirtualizedList::of($items, $cell, $key)->columns($columns);
    }
}
