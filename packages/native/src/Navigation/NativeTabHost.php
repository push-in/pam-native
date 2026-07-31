<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use InvalidArgumentException;
use JsonException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

/** Retained platform tab controller shared by bottom, rail and top tabs. */
final class NativeTabHost extends Element
{
    /**
     * @param list<array{name: string, label: string, badge: ?string}> $items
     * @param list<Renderable> $screens
     * @throws JsonException
     */
    public static function make(
        array $items,
        int $selectedIndex,
        int $position,
        int $activeColor,
        int $inactiveColor,
        int $backgroundColor,
        int $indicatorColor,
        bool $swipeEnabled,
        bool $scrollEnabled,
        array $screens,
        Closure $onSelect,
    ): self {
        if ($items === [] || count($items) !== count($screens) || count($items) > 32) {
            throw new InvalidArgumentException('Native tabs require one to 32 matching items and screens.');
        }
        if ($selectedIndex < 1 || $selectedIndex > count($items) || $position < 1 || $position > 3) {
            throw new InvalidArgumentException('Native tab indexes and positions are sequential one-based values.');
        }

        return (new self(NodeKind::TabHost))
            ->withChildren($screens)
            ->withProperty(PropKey::FlexGrow, 1.0)
            ->withProperty(PropKey::TabItems, json_encode($items, JSON_THROW_ON_ERROR))
            ->withProperty(PropKey::TabSelectedIndex, $selectedIndex)
            ->withProperty(PropKey::TabPosition, $position)
            ->withProperty(PropKey::TabActiveColor, $activeColor)
            ->withProperty(PropKey::TabInactiveColor, $inactiveColor)
            ->withProperty(PropKey::TabBackgroundColor, $backgroundColor)
            ->withProperty(PropKey::TabIndicatorColor, $indicatorColor)
            ->withProperty(PropKey::TabSwipeEnabled, $swipeEnabled)
            ->withProperty(PropKey::TabScrollEnabled, $scrollEnabled)
            ->withEvent(EventKind::Change, $onSelect);
    }
}
