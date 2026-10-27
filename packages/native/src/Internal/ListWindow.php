<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\LayoutEvent;
use Pam\Native\PropKey;
use Pam\Native\ScrollPhaseEvent;
use Pam\Native\UI\View;
use WeakMap;
use WeakReference;

use function array_sum;
use function count;
use function abs;
use function array_intersect_key;
use function hash;
use function is_string;
use function max;
use function min;
use function strlen;

/**
 * PHP side of a template `<VirtualizedList windowSize="N">` whose rows come
 * from a `p-for` loop (React Native FlatList `windowSize`).
 *
 * Only the rows within `(N - 1) / 2` screens above and below the viewport
 * are rendered; the others become one childless placeholder view each, with
 * the row's last measured height (or the list's estimated row height), so
 * the content height, the scroll position and the native cell recycling
 * stay put. Their components unmount and the encoder forgets their nodes.
 *
 * The viewport comes from the list's own scroll phase events (drag start,
 * release with its fling velocity, momentum end), never from per-frame
 * scroll events: a fling is materialized up to its predicted landing as
 * soon as the finger lifts. Row heights and the offset of the first row
 * come from each row's layout event, reported once per row mount.
 *
 * @internal
 */
final class ListWindow
{
    /** Viewport height used until the list reports its own. */
    private const VIEWPORT_FALLBACK = 900.0;

    /**
     * Seconds of release velocity a fling travels (UIScrollView normal
     * deceleration covers ~0.5 s of it, Android's OverScroller a little
     * more), capped in screens.
     */
    private const FLING_SECONDS = 0.6;
    private const FLING_MAX_SCREENS = 16.0;

    /** @var WeakMap<Component, array<string, self>>|null */
    private static ?WeakMap $windows = null;

    /** @var WeakReference<Component> */
    private WeakReference $scope;

    private int $size = 5;
    private float $estimate = 600.0;
    private float $viewport = 0.0;
    private float $low = 0.0;
    private float $high = 0.0;
    private ?float $jump = null;
    private ?int $request = null;
    private float $base = 0.0;

    /** @var array<int|string, array<int, float>> measured heights by row identity and part */
    private array $heights = [];

    /** @var list<int|string> row identities of the last render, in order */
    private array $keys = [];

    /** @var array<int|string, true> rows the last render mounted */
    private array $mounted = [];

    /** @var array<int|string, array{0: float, 1: Element}> */
    private array $placeholders = [];

    /** @var WeakMap<Element, Element> rendered rows with their layout observer */
    private WeakMap $observed;

    private function __construct(Component $scope, public readonly string $path)
    {
        $this->scope = WeakReference::create($scope);
        $this->observed = new WeakMap();
    }

    public static function for(Component $scope, string $path): self
    {
        self::$windows ??= new WeakMap();
        $windows = self::$windows[$scope] ?? [];
        $window = $windows[$path] ?? null;
        if ($window === null) {
            $window = new self($scope, $path);
            $windows[$path] = $window;
            self::$windows[$scope] = $windows;
        }

        return $window;
    }

    /**
     * Applies the list's attributes of this render. A new scroll request
     * with a target offset also materializes the rows around that offset
     * until the next scroll phase.
     */
    public function configure(int $size, float $estimate, ?int $request, float $targetOffset): void
    {
        $this->size = max(1, $size);
        if ($estimate > 0.0) {
            $this->estimate = $estimate;
        }
        if ($request !== null && $request !== $this->request) {
            if ($this->request !== null && $targetOffset >= 0.0) {
                $this->jump = $targetOffset;
            }
            $this->request = $request;
        }
    }

    /**
     * The rows to render among $identities (the loop's rows in order).
     *
     * @param list<int|string> $identities
     * @return array<int|string, true>
     */
    public function plan(array $identities): array
    {
        $this->keys = $identities;
        if (count($this->heights) > count($identities) || count($this->placeholders) > count($identities)) {
            $present = [];
            foreach ($identities as $identity) {
                $present[$identity] = true;
            }
            $this->heights = array_intersect_key($this->heights, $present);
            $this->placeholders = array_intersect_key($this->placeholders, $present);
        }

        return $this->mounted = $this->compute();
    }

    /** Scroll phase of the list: `ScrollBeginDrag`, `ScrollEndDrag` or `MomentumScrollEnd`. */
    public function phase(EventKind $kind, string $payload): void
    {
        $event = ScrollPhaseEvent::fromPayload($payload);
        $y = $event->y;
        $land = $y;
        if ($kind === EventKind::ScrollEndDrag) {
            $limit = self::FLING_MAX_SCREENS * $this->viewportHeight();
            $land = $y + max(-$limit, min($limit, $event->velocityY * self::FLING_SECONDS));
        }
        $this->low = min($y, $land);
        $this->high = max($y, $land);
        $this->jump = null;
        $this->update();
    }

    /** Layout of the list itself: its height is the viewport. */
    public function viewport(string $payload): void
    {
        $height = LayoutEvent::fromPayload($payload)->height;
        if ($height <= 0.0 || abs($height - $this->viewport) < 1.0) {
            return;
        }
        $this->viewport = $height;
        $this->update();
    }

    /** Layout of a rendered row (part $part of the row's output). */
    public function layout(int|string $identity, int $part, string $payload): void
    {
        $event = LayoutEvent::fromPayload($payload);
        if ($event->height <= 0.0) {
            return;
        }
        $this->heights[$identity][$part] = $event->height;
        if ($part === 0 && ($this->keys[0] ?? null) === $identity) {
            $this->base = $event->y;
        }
    }

    /** The rendered row element with this window's layout observer. */
    public function observe(Element $row, int|string $identity, int $part): Element
    {
        $observed = $this->observed[$row] ?? null;
        if ($observed !== null) {
            return $observed;
        }
        $window = $this;
        $inner = $row->events()[EventKind::Layout->value] ?? null;
        $observer = $inner === null
            ? #[\Pam\Native\Attributes\PureHandler] static function (mixed $payload = '') use ($window, $identity, $part): void {
                $window->layout($identity, $part, is_string($payload) ? $payload : '');
            }
            : static function (mixed $payload = '') use ($window, $identity, $part, $inner): mixed {
                $window->layout($identity, $part, is_string($payload) ? $payload : '');

                return $inner($payload);
            };
        $observed = $row->on(EventKind::Layout, $observer);
        $this->observed[$row] = $observed;

        return $observed;
    }

    /** The childless stand-in of a row outside the window. */
    public function placeholder(int|string $identity): Element
    {
        $height = $this->height($identity);
        $cached = $this->placeholders[$identity] ?? null;
        if ($cached !== null && $cached[0] === $height) {
            return $cached[1];
        }
        $key = (string) $identity;
        $key = strlen($key) > 100 ? 'pam-window#'.hash('xxh3', $key) : 'pam-window:'.$key;
        $element = View::make()
            ->key($key)
            ->__pamWithProperties([
                PropKey::Height->value => $height,
                PropKey::FlexShrink->value => 0.0,
            ]);
        $this->placeholders[$identity] = [$height, $element];

        return $element;
    }

    /** @return array<int|string, true> */
    private function compute(): array
    {
        $viewport = $this->viewportHeight();
        $margin = ($this->size - 1) / 2 * $viewport;
        $ranges = [[$this->low - $margin, $this->high + $viewport + $margin]];
        if ($this->jump !== null) {
            $ranges[] = [$this->jump - $margin, $this->jump + $viewport + $margin];
        }
        $mounted = [];
        $top = $this->base;
        foreach ($this->keys as $identity) {
            $bottom = $top + $this->height($identity);
            foreach ($ranges as [$from, $to]) {
                if ($bottom >= $from && $top <= $to) {
                    $mounted[$identity] = true;
                    break;
                }
            }
            $top = $bottom;
        }

        return $mounted;
    }

    private function update(): void
    {
        $next = $this->compute();
        if ($next === $this->mounted) {
            return;
        }
        $this->mounted = $next;
        $scope = $this->scope->get();
        if ($scope !== null) {
            DependencyTracker::markDirty($scope);
            Runtime::scheduleRender();
        }
    }

    private function height(int|string $identity): float
    {
        $parts = $this->heights[$identity] ?? null;

        return $parts === null ? $this->estimate : (float) array_sum($parts);
    }

    private function viewportHeight(): float
    {
        return $this->viewport > 0.0 ? $this->viewport : self::VIEWPORT_FALLBACK;
    }

    /** @internal Test hook: [mounted rows, viewport, base]. */
    public function state(): array
    {
        return [$this->mounted, $this->viewportHeight(), $this->base];
    }
}
