<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\LayoutEvent;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\UI\View;
use stdClass;
use WeakMap;
use WeakReference;

use function ceil;
use function count;
use function intdiv;
use function is_float;
use function is_int;
use function is_string;
use function ksort;
use function max;
use function usort;

/**
 * The window of a `VirtualizedList` with `windowSize` (React Native FlatList
 * `windowSize`), kept across renders for one list of one component.
 *
 * The rows are materialized by the tree encoders like any windowed list
 * (rows outside the window are `ListPlaceholder` nodes and the hosts report
 * the rows on screen with `ListWindow` events when a placeholder comes
 * near), but this window is sized in screens instead of rows: it keeps
 * `(windowSize - 1) / 2` screens of rows above and below the visible ones,
 * a screen being the rows the host reports visible. Rows that leave it go
 * back to placeholders and their components unmount.
 *
 * Each placeholder has the height its row last measured (one layout event
 * per row mount), or the list's estimate before that, plus the row's own
 * margins, so the content height and the scroll position do not move when
 * rows above the viewport are released. A scroll request to an offset
 * materializes the rows around it before the list gets there.
 *
 * @internal
 */
final class ListWindow
{
    /** Viewport height used to plan rows before the host reports any. */
    private const VIEWPORT_FALLBACK = 900.0;

    private const MARGIN_KEYS = [
        PropKey::Margin->value => true,
        PropKey::MarginVertical->value => true,
        PropKey::MarginTop->value => true,
        PropKey::MarginBottom->value => true,
    ];

    /** @var WeakMap<object, array<string, self>>|null */
    private static ?WeakMap $windows = null;

    private static ?stdClass $detached = null;

    /** @var array<int, WeakReference<self>> windows by the node id of their list */
    private static array $lists = [];

    private int $size = 5;
    private float $estimate = 600.0;

    /** @var list<array{0: int, 1: int}>|null materialized [start, end) rows */
    private ?array $ranges = null;

    private ?int $request = null;

    /** Whether the host reported its rows yet (until then the top is planned). */
    private bool $reported = false;

    /** @var list<string> row keys of the last encode, in order */
    private array $keys = [];

    /** @var array<int, true> rows always materialized (list header and footer) */
    private array $fixed = [];

    /** @var array<string, float> measured row heights by key */
    private array $heights = [];

    /** @var array<string, array<int, mixed>> margin properties of each row's root */
    private array $margins = [];

    /** @var array<string, array{0: float, 1: Element}> */
    private array $placeholders = [];

    /** @var WeakMap<Element, Element> built rows with their layout observer */
    private WeakMap $observed;

    private function __construct()
    {
        $this->observed = new WeakMap();
    }

    /** The window of list $path rendered by $owner (null: outside any component). */
    public static function for(?object $owner, string $path): self
    {
        $owner ??= self::$detached ??= new stdClass();
        self::$windows ??= new WeakMap();
        $windows = self::$windows[$owner] ?? [];
        $window = $windows[$path] ?? null;
        if ($window === null) {
            $window = new self();
            $windows[$path] = $window;
            self::$windows[$owner] = $windows;
        }

        return $window;
    }

    /** The window last encoded as list node $listId. */
    public static function forList(int $listId): ?self
    {
        return (self::$lists[$listId] ?? null)?->get();
    }

    public function configure(int $size): void
    {
        $this->size = max(1, $size);
    }

    public function size(): int
    {
        return $this->size;
    }

    /**
     * Called by the encoders for list node $listId: the rows to materialize
     * now, or null when the list is not windowed (horizontal lists).
     *
     * @return list<array{0: int, 1: int}>|null
     */
    public function ranges(int $listId, Element $list): ?array
    {
        $properties = $list->properties;
        if ($list->kind !== NodeKind::VirtualList || ($properties[PropKey::ListHorizontal->value] ?? false) === true) {
            return null;
        }
        if ((self::$lists[$listId] ?? null)?->get() !== $this) {
            foreach (self::$lists as $id => $reference) {
                if ($reference->get() === null) {
                    unset(self::$lists[$id]);
                }
            }
            self::$lists[$listId] = WeakReference::create($this);
        }
        $estimate = $properties[PropKey::ListRowHeight->value] ?? null;
        if ((is_float($estimate) || is_int($estimate)) && $estimate > 0) {
            $this->estimate = (float) $estimate;
        }
        $this->keys = $list->lazyRows?->keys ?? [];
        $this->fixed = $list->lazyRows?->fixed ?? [];
        if (!$this->reported) {
            // Rows joining before the host reports any (a list growing in
            // chunks, measured headers) are planned from the top again.
            $this->ranges = [$this->rowsAround(0.0)];
        }
        $request = $properties[PropKey::ScrollRequest->value] ?? null;
        if (is_int($request) && $request !== $this->request) {
            $target = $properties[PropKey::ScrollTargetOffset->value] ?? -1.0;
            if ($this->request !== null && (is_float($target) || is_int($target)) && $target >= 0) {
                [$start, $end] = $this->rowsAround((float) $target);
                $this->ranges = self::addRange($this->ranges, $start, $end);
                $this->reported = true;
            }
            $this->request = $request;
        }
        $ranges = $this->ranges;
        foreach ($this->fixed as $row => $_) {
            $ranges = self::addRange($ranges, $row, $row + 1);
        }

        return $ranges;
    }

    /**
     * The rows a first frame of $rows materializes (or the current window
     * once planned), for LazyRows::shown().
     *
     * @return list<array{0: int, 1: int}>
     */
    public function firstRows(LazyRows $rows): array
    {
        if ($this->ranges === null || !$this->reported) {
            $this->keys = $rows->keys;
            $this->fixed = $rows->fixed;
            $ranges = [$this->rowsAround(0.0)];
        } else {
            $ranges = $this->ranges;
        }
        foreach ($rows->fixed as $row => $_) {
            $ranges = self::addRange($ranges, $row, $row + 1);
        }

        return $ranges;
    }

    /**
     * The host shows rows $first..$last: keeps `(windowSize - 1) / 2` of
     * those screens materialized on each side. True when the window moved.
     */
    public function extend(int $first, int $last): bool
    {
        if ($first < 0 || $last < 0) {
            return false;
        }
        if ($last < $first) {
            [$first, $last] = [$last, $first];
        }
        $this->reported = true;
        $margin = (int) ceil(($this->size - 1) / 2 * ($last - $first + 1));
        $start = max(0, $first - $margin);
        $end = $last + $margin + 1;
        // Half a margin of slack each way: rows are built and released in
        // batches, not on every row the host scrolls past.
        $slack = intdiv($margin, 2);
        if ($this->ranges !== null && count($this->ranges) === 1) {
            [$from, $to] = $this->ranges[0];
            if (
                $from <= max(0, $first - $slack) && $to >= $last + $slack + 1
                && $from >= $start - $slack && $to <= $end + $slack
            ) {
                return false;
            }
        }
        $this->ranges = [[$start, $end]];

        return true;
    }

    /** The built row with this window's layout observer. */
    public function observe(Element $row, string $key): Element
    {
        $observed = $this->observed[$row] ?? null;
        if ($observed !== null) {
            return $observed;
        }
        $margins = [];
        foreach ($row->properties as $property => $value) {
            if (isset(self::MARGIN_KEYS[$property])) {
                $margins[$property] = $value;
            }
        }
        if ($margins === []) {
            unset($this->margins[$key]);
        } else {
            $this->margins[$key] = $margins;
        }
        $window = WeakReference::create($this);
        $inner = $row->events()[EventKind::Layout->value] ?? null;
        $observer = $inner === null
            ? #[\Pam\Native\Attributes\PureHandler] static function (mixed $payload = '') use ($window, $key): void {
                $window->get()?->measured($key, is_string($payload) ? $payload : '');
            }
            : static function (mixed $payload = '') use ($window, $key, $inner): mixed {
                $window->get()?->measured($key, is_string($payload) ? $payload : '');

                return $inner($payload);
            };
        $observed = $row->on(EventKind::Layout, $observer);
        $this->observed[$row] = $observed;

        return $observed;
    }

    /** Layout of a materialized row. */
    public function measured(string $key, string $payload): void
    {
        $height = LayoutEvent::fromPayload($payload)->height;
        if ($height > 0.0) {
            $this->heights[$key] = $height;
        }
    }

    /** The childless stand-in of a row outside the window. */
    public function placeholder(string $key): Element
    {
        $height = $this->heights[$key] ?? $this->estimate;
        $cached = $this->placeholders[$key] ?? null;
        if ($cached !== null && $cached[0] === $height) {
            return $cached[1];
        }
        $properties = [
            PropKey::Height->value => $height,
            PropKey::ListPlaceholder->value => true,
        ] + ($this->margins[$key] ?? []);
        ksort($properties);
        $element = View::__pamCreate(NodeKind::View, [], $properties, null, $key);
        if (count($this->placeholders) > 2 * max(64, count($this->keys))) {
            // Keys of items that left the list.
            $this->placeholders = [];
            $this->heights = array_intersect_key($this->heights, array_flip($this->keys));
            $this->margins = array_intersect_key($this->margins, $this->heights);
        }
        $this->placeholders[$key] = [$height, $element];

        return $element;
    }

    /**
     * Rows within the window around a viewport whose top is at $offset.
     *
     * @return array{0: int, 1: int}
     */
    private function rowsAround(float $offset): array
    {
        $margin = ($this->size - 1) / 2 * self::VIEWPORT_FALLBACK;
        $from = $offset - $margin;
        $to = $offset + self::VIEWPORT_FALLBACK + $margin;
        $start = null;
        $end = 0;
        $top = 0.0;
        foreach ($this->keys as $row => $key) {
            if ($top > $to) {
                break;
            }
            // The header and footer count only once measured.
            $bottom = $top + ($this->heights[$key] ?? (isset($this->fixed[$row]) ? 0.0 : $this->estimate));
            if ($bottom >= $from) {
                $start ??= $row;
                $end = $row + 1;
            }
            $top = $bottom;
        }

        return [$start ?? 0, max($end, ($start ?? 0) + 1)];
    }

    /**
     * @param list<array{0: int, 1: int}> $ranges
     * @return list<array{0: int, 1: int}>
     */
    private static function addRange(array $ranges, int $start, int $end): array
    {
        $ranges[] = [$start, $end];
        usort($ranges, static fn (array $a, array $b): int => $a[0] <=> $b[0]);
        $merged = [];
        foreach ($ranges as [$from, $to]) {
            $last = count($merged) - 1;
            if ($last >= 0 && $from <= $merged[$last][1]) {
                $merged[$last][1] = max($merged[$last][1], $to);
            } else {
                $merged[] = [$from, $to];
            }
        }

        return $merged;
    }

    /** @internal Test hook: [materialized ranges, measured heights by key]. */
    public function state(): array
    {
        return [$this->ranges, $this->heights];
    }
}
