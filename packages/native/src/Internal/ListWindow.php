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

    /** Row count before the rows last changed (a host report with it is stale). */
    private int $previousRowCount = -1;

    /** `initialScrollIndex` of the list (null: opens at its top). */
    private ?int $initialIndex = null;

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

    /** @var array<string, NodeKind> root kind of each row last built */
    private array $kinds = [];

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

    /**
     * The row the list opens at (`initialScrollIndex`; past the last row
     * means the last row): until the host reports its rows, the window is
     * planned around it instead of the top, so a list opened at its end (a
     * conversation) materializes its newest rows.
     */
    public function startAt(?int $index): void
    {
        $this->initialIndex = $index !== null && $index > 0 ? $index : null;
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
        $keys = $list->lazyRows?->keys ?? [];
        if ($this->reported && $this->ranges !== null && $this->keys !== [] && $keys !== $this->keys) {
            // Rows joined or left before the window (an older page above a
            // conversation): the window follows its rows by key, not by
            // index, or the rows on screen would be released and others
            // built in their place.
            $this->ranges = self::rekeyed($this->ranges, $this->keys, $keys);
        }
        if ($keys !== $this->keys) {
            $this->previousRowCount = count($this->keys);
        }
        $this->keys = $keys;
        $this->fixed = $list->lazyRows?->fixed ?? [];
        if (!$this->reported) {
            // Rows joining before the host reports any (a list growing in
            // chunks, measured headers) are planned from the start again.
            $this->ranges = [$this->rowsAround($this->plannedOffset())];
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
            $ranges = [$this->rowsAround($this->plannedOffset())];
        } else {
            $ranges = $this->ranges;
        }
        foreach ($rows->fixed as $row => $_) {
            $ranges = self::addRange($ranges, $row, $row + 1);
        }

        return $ranges;
    }

    /**
     * Rows a fling path may build, in screens: a longer path keeps the side
     * it lands on. Counted in screens, not windows: a wide window (a chat's
     * windowSize 11) already covers most flings.
     */
    private const PATH_SCREENS = 10;

    /**
     * The host shows rows $first..$last: keeps `(windowSize - 1) / 2` of
     * those screens materialized on each side. True when the window moved.
     *
     * A released fling also names $path, the rows it sweeps and the screen
     * it lands on: they are materialized at once, in one frame, and nothing
     * is released while it moves (a fling crossing ten screens moved the
     * window, building and releasing rows, a frame per screen). Rows behind
     * are released with the next move that has to build rows.
     *
     * @param array{0: int, 1: int}|null $path
     */
    public function extend(int $first, int $last, ?int $rows = null, ?array $path = null): bool
    {
        if ($first < 0 || $last < 0) {
            return false;
        }
        if ($rows !== null && $rows !== count($this->keys) && $rows === $this->previousRowCount) {
            // Reported before rows joined or left (an older page landed
            // meanwhile): its indexes name other rows now, and moving the
            // window there released the rows on screen. The host reports
            // again once it shows the new rows.
            return false;
        }
        if ($last < $first) {
            [$first, $last] = [$last, $first];
        }
        // The first report replaces the planned window (sized on a guess).
        $planned = !$this->reported;
        $this->reported = true;
        $margin = (int) ceil(($this->size - 1) / 2 * ($last - $first + 1));
        $start = max(0, $first - $margin);
        $end = $last + $margin + 1;
        // Half a margin of slack each way: rows are built and released in
        // batches, not on every row the host scrolls past.
        $slack = intdiv($margin, 2);
        $needFrom = max(0, $first - $slack);
        $needTo = $last + $slack + 1;
        $window = $end - $start;
        $span = self::PATH_SCREENS * ($last - $first + 1);
        if ($path !== null) {
            [$pathFirst, $pathLast] = $path[0] <= $path[1] ? $path : [$path[1], $path[0]];
            $next = [[$start, $end]];
            if ($pathLast - $pathFirst + 1 > $span) {
                // Too long to build whole: the rows on screen and the side
                // it lands on (the end it moves to); the middle stays
                // placeholders the fling passes over.
                if ($pathLast > $last) {
                    $pathFirst = $pathLast - $span + 1;
                } else {
                    $pathLast = $pathFirst + $span - 1;
                }
            }
            // Past the path, at most two screens: a wide window (windowSize
            // 11, five screens each side) built five more screens past the
            // landing in the same frame; the next gesture extends it.
            $landingMargin = min($margin, 2 * ($last - $first + 1));
            $next = self::addRange($next, max(0, $pathFirst - $landingMargin), $pathLast + $landingMargin + 1);
            // Built once per fling: a long path's middle stays placeholders
            // instead of being chased screen by screen.
            // The path was built with a margin past its landing: a landing
            // projected a little farther as the fling runs is still covered.
            $covered = $this->ranges !== null
                && self::covered($this->ranges, $pathFirst, $pathLast + 1);
            if ($covered && self::rowsIn($this->ranges) <= $span + $window + 2 * $margin) {
                // A fling over built rows releases nothing while it moves
                // (bounded: a window grown past its path span shrinks).
                return false;
            }
            $this->ranges = $next;

            return true;
        }
        if (!$planned && $this->ranges !== null && self::covered($this->ranges, $needFrom, $needTo)) {
            if (count($this->ranges) === 1) {
                [$from, $to] = $this->ranges[0];
                if ($from >= $start - $slack && $to <= $end + $slack) {
                    return false;
                }
            }
            // Rows a fling path left built stay until a move has to build
            // rows (one frame then builds and releases), up to the path span:
            // releasing them alone cost a frame at the start of each gesture.
            if (self::rowsIn($this->ranges) <= $span + $window + 2 * $margin) {
                return false;
            }
        }
        $this->ranges = [[$start, $end]];

        return true;
    }

    /** The built row with this window's layout observer. */
    public function observe(Element $row, string $key): Element
    {
        $this->kinds[$key] = $row->kind;
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
        // The placeholder of a row built before takes its root kind, so the
        // row keeps its native node (and the list its anchor and pixel
        // position) when the window releases or builds it again.
        $kind = $this->kinds[$key] ?? NodeKind::View;
        $cached = $this->placeholders[$key] ?? null;
        if ($cached !== null && $cached[0] === $height && $cached[1]->kind === $kind) {
            return $cached[1];
        }
        $properties = [
            PropKey::Height->value => $height,
            PropKey::ListPlaceholder->value => true,
        ] + ($this->margins[$key] ?? []);
        ksort($properties);
        $element = View::__pamCreate($kind, [], $properties, null, $key);
        if (count($this->placeholders) > 2 * max(64, count($this->keys))) {
            // Keys of items that left the list.
            $this->placeholders = [];
            $this->heights = array_intersect_key($this->heights, array_flip($this->keys));
            $this->kinds = array_intersect_key($this->kinds, array_flip($this->keys));
            $this->margins = array_intersect_key($this->margins, $this->heights);
        }
        $this->placeholders[$key] = [$height, $element];

        return $element;
    }

    /**
     * $ranges over $previous keys, mapped onto $next keys: each range spans
     * from its first to its last row still present (rows that joined inside
     * it are built too); a range whose rows all left is dropped.
     *
     * @param list<array{0: int, 1: int}> $ranges
     * @param list<string> $previous
     * @param list<string> $next
     * @return list<array{0: int, 1: int}>
     */
    private static function rekeyed(array $ranges, array $previous, array $next): array
    {
        $positions = array_flip($next);
        $mapped = [];
        foreach ($ranges as [$from, $to]) {
            $start = null;
            for ($row = max(0, $from); $row < min($to, count($previous)); $row++) {
                if (isset($positions[$previous[$row]])) {
                    $start = $positions[$previous[$row]];
                    break;
                }
            }
            $end = null;
            for ($row = min($to, count($previous)) - 1; $row >= max(0, $from); $row--) {
                if (isset($positions[$previous[$row]])) {
                    $end = $positions[$previous[$row]];
                    break;
                }
            }
            if ($start !== null && $end !== null && $end >= $start) {
                $mapped = self::addRange($mapped, $start, $end + 1);
            }
        }

        return $mapped;
    }

    /** Viewport top the list opens at: a screen above its initial row's bottom. */
    private function plannedOffset(): float
    {
        if ($this->initialIndex === null || $this->keys === []) {
            return 0.0;
        }
        $last = min($this->initialIndex, count($this->keys) - 1);
        $bottom = 0.0;
        foreach ($this->keys as $row => $key) {
            $bottom += $this->heights[$key] ?? (isset($this->fixed[$row]) ? 0.0 : $this->estimate);
            if ($row >= $last) {
                break;
            }
        }

        return max(0.0, $bottom - self::VIEWPORT_FALLBACK);
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

    /** @param list<array{0: int, 1: int}> $ranges */
    private static function covered(array $ranges, int $start, int $end): bool
    {
        foreach ($ranges as [$from, $to]) {
            if ($from <= $start && $to >= $end) {
                return true;
            }
        }

        return false;
    }

    /** @param list<array{0: int, 1: int}> $ranges */
    private static function rowsIn(array $ranges): int
    {
        $rows = 0;
        foreach ($ranges as [$from, $to]) {
            $rows += $to - $from;
        }

        return $rows;
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
