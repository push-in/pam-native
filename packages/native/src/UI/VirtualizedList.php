<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\UI\Concerns\HasRefreshControl;
use Pam\Native\UI\Concerns\HasScrollPhaseEvents;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\Internal\LazyRows;
use Pam\Native\Internal\Wire;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\ScrollTargetAlignment;
use Pam\Native\Renderable;

use function is_bool;
use function count;
use function is_array;
use function is_string;

final class VirtualizedList extends Element
{
    use HasRefreshControl;
    use HasScrollPhaseEvents;

    /**
     * Creates a lazily mounted native list from arbitrary PAM components.
     *
     * Assign stable keys to stateful or reorderable children. Null and false
     * items are holes that keep the following siblings' identities stable.
     *
     * Rows may also be closures returning the row (or a generator / iterable
     * of rows and closures): a closure row is called only when the list
     * window reaches it, and is identified by its position.
     *
     * @param Renderable|Closure(): (Renderable|false|null)|iterable<Renderable|Closure|false|null>|array<string>|false|null ...$items
     */
    public static function make(Renderable|Closure|iterable|false|null ...$items): self
    {
        if (count($items) === 1 && $items[0] instanceof \Traversable) {
            $items = iterator_to_array($items[0], false);
        }
        foreach ($items as $item) {
            if ($item instanceof Closure) {
                return self::deferred($items);
            }
        }
        if (count($items) === 1 && is_array($items[0])) {
            $strings = [];
            foreach ($items[0] as $item) {
                if (!is_string($item)) {
                    throw new InvalidArgumentException(
                        'Legacy VirtualizedList array items must be strings; '
                        .'pass renderable elements as variadic arguments for rich cells.',
                    );
                }
                $strings[] = $item;
            }

            return (new self(NodeKind::List))->withProperty(
                PropKey::Items,
                new BinaryValue(Wire::stringList($strings)),
            );
        }

        /** @var list<Renderable|false|null> $items */
        return (new self(NodeKind::VirtualList))->withChildren($items);
    }

    /**
     * A list driven by data, like React Native's FlatList/FlashList `data` +
     * `renderItem` + `keyExtractor`: `$row($item, $index)` is called only for
     * the rows that are shown (and a margin around them), never for all of
     * them up front. Keys default to the item's `key` or `id` (array key or
     * public property), then to its index.
     *
     * @param iterable<mixed> $items
     * @param Closure(mixed, int): (Renderable|false|null) $row
     * @param (Closure(mixed, int): (string|int))|null $key
     */
    public static function of(iterable $items, Closure $row, ?Closure $key = null): self
    {
        $values = is_array($items) ? array_values($items) : iterator_to_array($items, false);
        $keys = [];
        foreach ($values as $index => $item) {
            $value = $key !== null ? $key($item, $index) : self::defaultKey($item, $index);
            $keys[] = (string) $value;
        }
        $list = new self(NodeKind::VirtualList);
        $list->lazyRows = new LazyRows($values, $keys, $row);

        return $list;
    }

    /**
     * Closure rows are built when the window reaches them; the other rows
     * are kept as given (keyed by their key, or by position).
     *
     * @param array<int|string, mixed> $items
     */
    private static function deferred(array $items): self
    {
        $rows = [];
        $keys = [];
        $seen = [];
        foreach (array_values($items) as $index => $item) {
            if ($item === null || $item === false) {
                continue;
            }
            if (!$item instanceof Closure && !$item instanceof Renderable) {
                throw new InvalidArgumentException('VirtualizedList rows must be renderable or closures returning one.');
            }
            $key = $item instanceof Element && $item->elementKey !== null ? $item->elementKey : '#'.$index;
            if (isset($seen[$key])) {
                throw new InvalidArgumentException("Duplicate VirtualizedList row key {$key}.");
            }
            $seen[$key] = true;
            $rows[] = $item;
            $keys[] = $key;
        }
        $list = new self(NodeKind::VirtualList);
        $list->lazyRows = new LazyRows(
            $rows,
            $keys,
            static fn (Renderable|Closure $row): Renderable|false|null => $row instanceof Closure ? $row() : $row,
        );

        return $list;
    }

    /**
     * React Native FlatList `windowSize`: only the rows within
     * `($size - 1) / 2` screens above and below the visible ones are built
     * and mounted (21 in React Native; 5 suits tall media rows). The other
     * rows are childless placeholders with the height each row last
     * measured, or the estimate (`estimatedRowHeight`) before it was ever
     * shown, so the content height and the scroll position do not move.
     * Rows leaving the window unmount their components (local state is
     * lost, as in React Native). Closure and data-driven rows
     * ({@see self::of()}) are only built inside the window.
     *
     * Without `windowSize`, vertical lists of more than 96 rows are windowed
     * automatically by rows (see docs/components.md). Several windowed lists
     * rendered by one component need distinct keys.
     */
    public function windowSize(int $size): self
    {
        $window = \Pam\Native\Internal\ListWindow::for(
            \Pam\Native\Internal\ComponentLifecycle::current(),
            'list:'.($this->elementKey ?? ''),
        );

        return $this->__pamWindow($window, $size);
    }

    /** @internal Applies a `windowSize` window (template and builder). */
    public function __pamWindow(\Pam\Native\Internal\ListWindow $window, int $size): self
    {
        $window->configure($size);
        $rows = $this->lazyRows ?? LazyRows::compose(array_values(array_filter(
            $this->children,
            static fn (mixed $child): bool => $child instanceof Element,
        )));
        if ($rows === null) {
            throw new InvalidArgumentException('VirtualizedList rows with windowSize need distinct keys.');
        }
        $copy = $this->withChildren([]);
        $copy->lazyRows = $rows->windowed($window);

        return $copy;
    }

    /** @internal Rows built by the encoders on demand (template p-for, see TemplateRenderer). */
    public static function __pamRows(LazyRows $rows): self
    {
        $list = new self(NodeKind::VirtualList);
        $list->lazyRows = $rows;

        return $list;
    }

    private static function defaultKey(mixed $item, int $index): string|int
    {
        if (is_array($item)) {
            foreach (['key', 'id'] as $field) {
                if (isset($item[$field]) && (is_string($item[$field]) || is_int($item[$field]))) {
                    return $item[$field];
                }
            }
        } elseif (is_object($item)) {
            foreach (['key', 'id'] as $field) {
                if (isset($item->{$field}) && (is_string($item->{$field}) || is_int($item->{$field}))) {
                    return $item->{$field};
                }
            }
        }

        return $index;
    }

    public function rowHeight(float $height): self
    {
        return $this->withProperty(PropKey::ListRowHeight, max(1.0, $height));
    }

    /**
     * Sets the fallback and prefetch estimate for cells without an authored
     * main-axis size. Explicit cell heights or widths remain authoritative.
     */
    public function estimatedRowHeight(float $height): self
    {
        return $this->rowHeight($height);
    }

    /**
     * Shows only the rows of keyed section `$section` among the rows that
     * set {@see Element::listSection()}; the other sections stay mounted
     * (layouts, views and images) and hidden, each with its own scroll
     * position. Changing it is a property update: no row is rebuilt or
     * remounted.
     */
    public function activeSection(string|int $section): self
    {
        return $this->withProperty(PropKey::ListActiveSection, (string) $section);
    }

    public function prefetch(int $items): self
    {
        return $this->withProperty(PropKey::ListPrefetch, min(32, max(1, $items)));
    }

    /**
     * React Native `keyboardShouldPersistTaps`: what a tap inside this
     * scroll view does while the soft keyboard is visible. `true` is
     * `Always`, `false` is `Never`. Without it, taps never dismiss the
     * keyboard (PAM's historical behaviour).
     */
    public function keyboardShouldPersistTaps(\Pam\Native\KeyboardPersistTaps|bool $mode): self
    {
        if (is_bool($mode)) {
            $mode = \Pam\Native\KeyboardPersistTaps::fromBool($mode);
        }

        return $this->withProperty(PropKey::ScrollKeyboardPersistTaps, $mode->value);
    }

    public function horizontal(bool $horizontal = true): self
    {
        return $this->withProperty(PropKey::ListHorizontal, $horizontal);
    }

    public function columns(int $columns): self
    {
        return $this->withProperty(PropKey::ListNumColumns, max(1, $columns));
    }

    public function inverted(bool $inverted = true): self
    {
        return $this->withProperty(PropKey::ListInverted, $inverted);
    }

    /**
     * Whether a list resting at its end (opened there with
     * `initialScrollIndex` or scrolled there) keeps the end visible when its
     * viewport resizes. Default true; pass false for lists in sheets/modals
     * that must keep their offset whatever the container animates.
     */
    public function followEndOnResize(bool $follow = true): self
    {
        return $this->withProperty(PropKey::ListFollowEndOnResize, $follow);
    }

    public function initialScrollIndex(int $index): self
    {
        return $this->withProperty(PropKey::ListInitialScrollIndex, max(0, $index));
    }

    public function removeClippedSubviews(bool $remove = true): self
    {
        return $this->withProperty(PropKey::ListRemoveClippedSubviews, $remove);
    }

    /** Content padding uses the native scroll insets; no extra wrapper or rows. */
    public function contentContainerStyle(\Pam\Native\Style $style): self
    {
        return $this->style($style);
    }

    public function scrollEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::ScrollEnabled, $enabled);
    }

    public function showsIndicator(bool $visible = true): self
    {
        return $this->withProperty(PropKey::ShowsScrollIndicator, $visible);
    }

    public function scrollRequest(
        int $request,
        string $targetTestId = '',
        float $targetOffset = -1.0,
        ScrollTargetAlignment $targetAlignment = ScrollTargetAlignment::Start,
        bool $animated = false,
    ): self {
        return $this
            ->withProperty(PropKey::ScrollTargetTestId, $targetTestId)
            ->withProperty(PropKey::ScrollTargetOffset, $targetOffset)
            ->withProperty(PropKey::ScrollTargetAlignment, $targetAlignment->value)
            ->withProperty(PropKey::ScrollAnimated, $animated)
            ->withProperty(PropKey::ScrollRequest, max(0, $request));
    }

    /** RN `pagingEnabled` for lists: one item per page (Reels), snapped natively. */
    public function pagingEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::ScrollPagingEnabled, $enabled);
    }

    public function onScroll(Closure $handler): self
    {
        return $this->withEvent(EventKind::Scroll, $handler);
    }

    public function onEndReached(Closure $handler, float $threshold = 0.5): self
    {
        return $this
            ->withProperty(PropKey::EndReachedThreshold, min(10.0, max(0.0, $threshold)))
            ->withEvent(EventKind::EndReached, $handler);
    }
}
