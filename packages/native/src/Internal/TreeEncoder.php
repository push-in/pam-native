<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\Protocol;
use Pam\Native\PropKey;
use WeakMap;

use function array_slice;
use function count;
use function is_bool;
use function is_float;
use function is_int;
use function is_string;
use function strlen;

final class TreeEncoder
{
    private const MAX_NODES = 100_000;

    /**
     * Vertical VirtualizedList/VirtualGrid with more rows than this are
     * materialized by window: rows outside it are sent as childless
     * placeholders (their own root properties plus ListPlaceholder), sized by
     * the list's estimate. Native reports the rows it shows (EventKind
     * ListWindow) and the window grows ahead of the scroll.
     */
    private const WINDOW_MIN_ROWS = 96;

    /** Rows materialized when a list first mounts (and around initialScrollIndex). */
    private const WINDOW_INITIAL_ROWS = 48;

    /**
     * Smallest margin materialized beyond the rows native reports visible.
     * Native asks again only when a placeholder comes within a screen, so a
     * wide margin keeps window growth (each one an O(rows) patch) rare.
     */
    private const WINDOW_MARGIN_ROWS = 64;

    /** @var array<string, list<array{0: int, 1: int}>> materialized [start, end) row ranges by list path */
    private array $windowRanges = [];

    /** @var array<int, array{0: string, 1: array<int, int>}> windowed list id => [path, row id => index] */
    private array $windowLists = [];

    /** Bumped whenever a window grows; stale windowed subtrees are re-encoded. */
    private int $windowStamp = 0;

    /** Windowed lists encoded or reused so far (marks the subtrees containing one). */
    private int $windowedLists = 0;

    /** @var WeakMap<Element, array<string, EncodedNode>> placeholder rows by element and path */
    private WeakMap $placeholders;

    private const EVENT_PROPERTIES = EventProperties::MAP;

    /** @var array<int, true> ids of the frame being encoded (outside reused subtrees) */
    private array $ids = [];

    /** @var array<string, int> */
    private array $identityIds = [];

    /** @var array<int, string> */
    private array $idIdentities = [];

    /** @var array<string, Closure> */
    private array $callbacks = [];

    /** @var array<int, EncodedNode> the committed tree: every node of the last frame, by id */
    private array $committed = [];

    /** @var WeakMap<Element, array<string, EncodedSubtree>> */
    private WeakMap $subtreeCache;

    /** @var list<EncodedNode|SubtreeReference> items of the subtree being encoded */
    private array $items = [];

    /** @var list<array{0: Element, 1: string, 2: EncodedSubtree}> */
    private array $built = [];

    /** The last frame: a reference to its root subtree. */
    private ?SubtreeReference $previousTree = null;

    /** @var array<int, EncodedSubtree> subtrees the last frame emitted, by object id */
    private array $emitted = [];

    /** @var array<int, EncodedSubtree> subtrees the frame being encoded emits */
    private array $emitting = [];

    /** @var array<int, true> ids the frame being encoded walked */
    private array $walked = [];

    private ?int $previousRoot = null;
    private int $nodeCount = 0;

    /**
     * @internal Encoded strings (validated UTF-8); the template renderer
     * reads it directly.
     *
     * @var array<string, string>
     */
    public static array $encodedStrings = [];

    /** Bytes of the long strings held by $encodedStrings. */
    private static int $encodedBytes = 0;

    /** @var array<int, array<string|int, string>> unkeyed child segments by kind and slot */
    private static array $segmentNames = [];

    /** @var array<int, string> encoded integers */
    private static array $encodedIntegers = [];

    /**
     * Child placement by parent path and segment: [child path, identity, id].
     * The cached path strings keep their hash across frames.
     *
     * @var array<string, array<string, array{0: string, 1: string, 2: int}>>
     */
    private array $childPaths = [];

    /** @var \WeakMap<Element, Element> scrollables lowered to a RefreshControl host */
    private \WeakMap $refreshHosts;

    /**
     * RN `refreshControl` props set directly on a ScrollView/list become a
     * RefreshControl host around it. Memoized per element so cached and
     * reused subtrees keep their identity across frames.
     */
    private function refreshHosted(Element $element): Element
    {
        $kind = $element->kind;
        if ($kind !== NodeKind::Scroll && $kind !== NodeKind::List
            && $kind !== NodeKind::VirtualList && $kind !== NodeKind::SectionList
        ) {
            return $element;
        }
        if (isset($this->refreshHosts[$element])) {
            return $this->refreshHosts[$element];
        }
        $host = $element->__pamRefreshHost();
        if ($host === null) {
            return $element;
        }
        $this->refreshHosts[$element] = $host;

        return $host;
    }

    public function __construct()
    {
        $this->subtreeCache = new WeakMap();
        $this->refreshHosts = new WeakMap();
        $this->placeholders = new WeakMap();
    }

    /**
     * @internal Native reported the rows a windowed list shows (node ids of
     * the first and last visible rows). Grows the materialized window ahead
     * of them and returns whether a new frame is needed.
     */
    public function extendWindow(int $listId, int $firstRowId, int $lastRowId): bool
    {
        $list = $this->windowLists[$listId] ?? null;
        if ($list === null) {
            return false;
        }
        [$path, $indexes] = $list;
        $first = $indexes[$firstRowId] ?? null;
        $last = $indexes[$lastRowId] ?? $first;
        if ($first === null || $last === null) {
            return false;
        }
        if ($last < $first) {
            [$first, $last] = [$last, $first];
        }
        $margin = max(self::WINDOW_MARGIN_ROWS, ($last - $first + 1) * 2);
        $ranges = $this->windowRanges[$path] ?? [];
        // Rows already materialized half a margin around the visible ones:
        // nothing to do yet (native also only asks near placeholders).
        if (self::covers($ranges, max(0, $first - intdiv($margin, 2)), $last + intdiv($margin, 2) + 1)) {
            return false;
        }
        $this->windowRanges[$path] = self::addRange($ranges, max(0, $first - $margin), $last + $margin + 1);
        ++$this->windowStamp;

        return true;
    }

    /** @param list<array{0: int, 1: int}> $ranges */
    private static function covers(array $ranges, int $start, int $end): bool
    {
        foreach ($ranges as [$from, $to]) {
            if ($from <= $start && $to >= $end) {
                return true;
            }
        }

        return false;
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
            $lastIndex = count($merged) - 1;
            if ($lastIndex >= 0 && $from <= $merged[$lastIndex][1]) {
                $merged[$lastIndex][1] = max($merged[$lastIndex][1], $to);
            } else {
                $merged[] = [$from, $to];
            }
        }

        return $merged;
    }

    /** @param list<array{0: int, 1: int}> $ranges */
    private static function inRanges(array $ranges, int $index): bool
    {
        foreach ($ranges as [$from, $to]) {
            if ($index >= $from && $index < $to) {
                return true;
            }
        }

        return false;
    }

    /** @return ?list<array{0: int, 1: int}> */
    private function windowFor(Element $list, string $path): ?array
    {
        if (
            $list->kind !== NodeKind::VirtualList
            || count($list->children) <= self::WINDOW_MIN_ROWS
            || ($list->properties[PropKey::ListHorizontal->value] ?? false) === true
        ) {
            return null;
        }
        $ranges = $this->windowRanges[$path] ?? null;
        if ($ranges === null) {
            $ranges = [[0, self::WINDOW_INITIAL_ROWS]];
            $initial = $list->properties[PropKey::ListInitialScrollIndex->value] ?? null;
            if (is_int($initial) && $initial > 0) {
                $ranges = self::addRange(
                    $ranges,
                    max(0, $initial - self::WINDOW_MARGIN_ROWS),
                    $initial + self::WINDOW_INITIAL_ROWS,
                );
            }
            $this->windowRanges[$path] = $ranges;
        }

        return $ranges;
    }

    /** Sticky headers, full-span headers/footers and single-node rows are never deferred. */
    private static function deferrable(Element $row): bool
    {
        return $row->children !== []
            && ($row->properties[PropKey::StickyHeader->value] ?? false) !== true
            && ($row->properties[PropKey::ListFullSpan->value] ?? false) !== true;
    }

    /**
     * A row outside the window: its root node (same id, kind and properties)
     * without children or events, flagged ListPlaceholder. Materializing it
     * later only creates its children and drops the flag.
     */
    private function encodePlaceholder(Element $row, int $id, int $parent, int $index, string $path): void
    {
        if (++$this->nodeCount > self::MAX_NODES) {
            throw new LogicException('Pam Native trees cannot exceed 100,000 nodes.');
        }
        $entries = $this->placeholders[$row] ?? [];
        $node = $entries[$path] ?? null;
        if ($node === null) {
            $properties = $row->properties;
            $properties[PropKey::ListPlaceholder->value] = true;
            ksort($properties, SORT_NUMERIC);
            $node = new EncodedNode($id, $parent, $index, $row->kind->value, self::encodeProperties($properties));
            $entries[$path] = $node;
            $this->placeholders[$row] = $entries;
        } elseif ($node->id !== $id || $node->parent !== $parent || $node->index !== $index) {
            $node = new EncodedNode($id, $parent, $index, $node->kind, $node->properties, $node->propertyBytes);
            $entries[$path] = $node;
            $this->placeholders[$row] = $entries;
        }
        $this->items[] = $node;
    }

    /**
     * @param array<int, mixed> $properties sorted by key
     * @return array<int, string>
     */
    private static function encodeProperties(array $properties): array
    {
        $encodedProperties = [];
        foreach ($properties as $property => $value) {
            if (is_string($value)) {
                $encoded = self::$encodedStrings[$value] ?? self::encodeString($value);
            } elseif (is_int($value)) {
                $encoded = self::$encodedIntegers[$value] ?? self::encodeInteger($value);
            } elseif (is_bool($value)) {
                $encoded = $value ? "\x04\x01" : "\x04\x00";
            } else {
                $encoded = self::encodeValue($value);
            }
            $encodedProperties[$property] = $encoded;
        }

        return $encodedProperties;
    }

    /**
     * Forces the next encoded tree to be a complete frame.
     *
     * This is used to recover synchronization when a native renderer rejects
     * an incremental patch. Stable element identities and subtree caches are
     * preserved, so recovery does not remount PHP components.
     */
    public function forceFullFrame(): void
    {
        $this->previousTree = null;
        $this->previousRoot = null;
        $this->emitted = [];
    }

    /**
     * @return array{frame: ?string, callbacks: array<string, Closure>, full: bool}
     */
    public function encode(Element $root): array
    {
        $root = $this->refreshHosted($root);
        $cachedRoot = ($this->subtreeCache[$root] ?? [])['root'] ?? null;

        if (
            $this->previousTree !== null
            && $cachedRoot !== null
            && ($cachedRoot->windowStamp < 0 || $cachedRoot->windowStamp === $this->windowStamp)
        ) {
            return [
                'frame' => null,
                'callbacks' => $cachedRoot->callbacks,
                'full' => false,
            ];
        }

        $this->ids = [];
        $this->callbacks = [];
        $this->items = [];
        $this->built = [];
        $this->nodeCount = 0;
        $rootId = $this->nodeId('root', $root->domIdentity, $root->kind->value);
        $this->encodeNode($root, $rootId, 0, 0, 'root');
        $tree = $this->items[0];
        \assert($tree instanceof SubtreeReference);
        $this->items = [];

        $full = $this->previousTree === null;
        $this->emitting = [];
        if ($full) {
            $nodes = [];
            $this->flatten($tree, $nodes);
            if (count($nodes) !== $this->nodeCount) {
                $this->collision($tree);
            }
            $frame = $this->fullFrame($rootId, $nodes);
        } else {
            [$frame, $nodes] = $this->patchFrame($rootId, $tree);
        }

        foreach ($this->built as [$element, $path, $subtree]) {
            $entries = $this->subtreeCache[$element] ?? [];
            $entries[$path] = $subtree;
            $this->subtreeCache[$element] = $entries;
        }
        $this->built = [];
        $this->previousRoot = $rootId;
        $this->previousTree = $tree;
        $this->committed = $nodes;
        foreach ($this->windowLists as $listId => [$listPath]) {
            if (!isset($nodes[$listId])) {
                unset($this->windowLists[$listId], $this->windowRanges[$listPath]);
            }
        }
        $this->emitted = $this->emitting;
        $this->emitting = [];
        $this->walked = [];

        return [
            'frame' => $frame,
            'callbacks' => $this->callbacks,
            'full' => $full,
        ];
    }

    /**
     * @internal Every node of the last encoded frame, in frame order.
     *
     * @return array<int, EncodedNode>
     */
    public function frameNodes(): array
    {
        $nodes = [];
        if ($this->previousTree !== null) {
            $emitting = $this->emitting;
            $this->flatten($this->previousTree, $nodes);
            $this->emitting = $emitting;
        }

        return $nodes;
    }

    private function encodeNode(
        Element $element,
        int $id,
        int $parent,
        int $index,
        string $path,
    ): void {
        $dom = $element->domIdentity;

        // Only the root, keyed/DOM and reusable (component) subtrees are cached.
        $cacheable = $element->elementKey !== null || $dom !== null || $element->reusable || $path === 'root';
        if ($cacheable) {
            $cached = ($this->subtreeCache[$element] ?? [])[$path] ?? null;
            if ($cached !== null && $cached->windowStamp >= 0) {
                if ($cached->windowStamp !== $this->windowStamp) {
                    // Contains a windowed list whose window grew since.
                    $cached = null;
                } else {
                    ++$this->windowedLists;
                }
            }

            if ($cached !== null) {
                $count = $cached->count;
                if ($this->nodeCount + $count > self::MAX_NODES) {
                    throw new LogicException('Pam Native trees cannot exceed 100,000 nodes.');
                }
                $root = $cached->items[0];
                if (!isset($this->emitted[spl_object_id($cached)])) {
                    // Not part of the last frame: its ids join this frame's
                    // reserved ids. The subtree root id was reserved by
                    // nodeId(); any other overlap is an identity collision.
                    $nodes = $cached->nodes();
                    $overlap = array_intersect_key($nodes, $this->ids);
                    if ($overlap !== [] && (count($overlap) > 1 || array_key_first($overlap) !== $id || $root->id !== $id)) {
                        throw new LogicException("Element identity collision at {$path}; assign a unique key.");
                    }
                    $this->ids += $nodes;
                }
                if ($root->index !== $index || $root->parent !== $parent) {
                    // A reused (memoized or keyed) subtree can sit at a new
                    // sibling position under the same path; only its root's
                    // placement changes, its descendants are unchanged.
                    $root = $root->placedAt($parent, $index);
                }
                $this->items[] = new SubtreeReference($cached, $root);
                $this->nodeCount += $count;
                if ($cached->callbacks !== []) {
                    $this->callbacks += $cached->callbacks;
                }

                return;
            }
        }

        $callbackStart = count($this->callbacks);
        $windowedBefore = $this->windowedLists;
        $window = $this->windowFor($element, $path);

        if (++$this->nodeCount > self::MAX_NODES) {
            throw new LogicException('Pam Native trees cannot exceed 100,000 nodes.');
        }
        $countStart = $this->nodeCount;

        $properties = $element->properties;
        foreach ($element->events as $eventKind => $callback) {
            $this->callbacks[$id.':'.$eventKind] = $callback;
            $properties[self::EVENT_PROPERTIES[$eventKind] ?? self::eventProperty($eventKind)] = true;
        }

        $cached = $element->encodedProperties;
        $propertyBytes = null;
        if ($window !== null) {
            $properties[PropKey::ListWindowed->value] = true;
            ksort($properties, SORT_NUMERIC);
            // Wire bytes are built lazily (EncodedNode::bytes()): only created
            // nodes and full frames need them.
            $encodedProperties = self::encodeProperties($properties);
        } elseif ($cached !== null && $cached[0] === $element->properties) {
            // Encoded by the template renderer from its per-element template.
            $encodedProperties = $cached[1];
            $propertyBytes = $cached[2];
        } else {
            ksort($properties, SORT_NUMERIC);
            $encodedProperties = self::encodeProperties($properties);
        }

        $node = new EncodedNode(
            $id,
            $parent,
            $index,
            $element->kind->value,
            $encodedProperties,
            $propertyBytes,
        );

        // Subtrees worth caching collect their own items; the rest of the
        // nodes join the enclosing subtree.
        $children = $element->children;
        $candidate = $path === 'root' || ($cacheable && $children !== []);
        if ($candidate) {
            $outer = $this->items;
            $this->items = [$node];
        } else {
            $this->items[] = $node;
        }

        if ($children !== []) {
            $segments = [];
            $placements = $this->childPaths[$path] ?? [];
            $learned = false;
            $rowIndexes = [];

            foreach ($children as $childIndex => $child) {
                $child = $this->refreshHosted($child);
                $childKind = $child->kind->value;
                $childKey = $child->elementKey;
                $childDom = $child->domIdentity;
                // Segment strings come from a table so repeated frames reuse
                // the same strings (and their hashes).
                $segment = $childDom !== null
                    ? 'dom:'.$childDom
                    : ($childKey !== null
                        ? 'key:'.$childKey
                        : (self::$segmentNames[$childKind][$child->identitySlot ?? $childIndex]
                            ??= self::segmentName($childKind, $child->identitySlot ?? $childIndex)));
                if (isset($segments[$segment]) && $childKey === null && $childDom === null) {
                    // A builder mixed slotted children with positional ones (for
                    // example template slot content composed in PHP); fall back to
                    // the output position instead of failing on a collision.
                    $segment = $childKind.':#'.$childIndex;
                }
                $segments[$segment] = true;
                $placement = $placements[$segment][$childKind] ?? null;
                if ($placement !== null) {
                    // Identity resolved in an earlier frame (nodeId() inlined).
                    [$childPath, , $childId] = $placement;
                    if (isset($this->ids[$childId])) {
                        throw new LogicException("Element identity collision at {$childPath}; assign a unique key.");
                    }
                    $this->ids[$childId] = true;
                } else {
                    $childPath = $path.'/'.$segment;
                    $identity = ($childDom === null ? $childPath : 'dom:'.$childDom).'|'.$childKind;
                    $childId = $this->identityIds[$identity] ?? null;
                    if ($childId === null) {
                        $childId = $this->nodeId($childPath, $childDom, $childKind);
                    } else {
                        if ($childId === 0 || isset($this->ids[$childId]) || ($this->idIdentities[$childId] ?? $identity) !== $identity) {
                            throw new LogicException("Element identity collision at {$childPath}; assign a unique key.");
                        }
                        $this->idIdentities[$childId] = $identity;
                        $this->ids[$childId] = true;
                    }
                    $placements[$segment][$childKind] = [$childPath, $identity, $childId];
                    $learned = true;
                }
                if ($window !== null) {
                    $rowIndexes[$childId] = $childIndex;
                    if (!self::inRanges($window, $childIndex) && self::deferrable($child)) {
                        $this->encodePlaceholder($child, $childId, $id, $childIndex, $childPath);
                        continue;
                    }
                }
                $this->encodeNode($child, $childId, $id, $childIndex, $childPath);
            }
            if ($learned) {
                $this->childPaths[$path] = $placements;
            }
            if ($window !== null) {
                $this->windowLists[$id] = [$path, $rowIndexes];
            }
        }
        if ($window !== null) {
            ++$this->windowedLists;
        }

        if ($candidate) {
            $items = $this->items;
            $nested = [];
            foreach ($items as $item) {
                if ($item instanceof SubtreeReference) {
                    $nested[] = $item->subtree;
                }
            }
            $callbackLength = count($this->callbacks) - $callbackStart;
            $subtree = new EncodedSubtree(
                $items,
                $nested,
                $this->nodeCount - $countStart + 1,
                $callbackLength === 0
                    ? []
                    : array_slice($this->callbacks, $callbackStart, $callbackLength, true),
            );
            if ($this->windowedLists !== $windowedBefore) {
                $subtree->windowStamp = $this->windowStamp;
            }
            $this->built[] = [$element, $path, $subtree];
            $outer[] = new SubtreeReference($subtree, $node);
            $this->items = $outer;
        }
    }

    /**
     * Collects every node of a subtree in frame order and marks the subtrees
     * this frame emits.
     *
     * @param array<int, EncodedNode> $nodes
     */
    private function flatten(SubtreeReference $reference, array &$nodes): void
    {
        $subtree = $reference->subtree;
        $this->emitting[spl_object_id($subtree)] = $subtree;
        $nodes[$reference->root->id] = $reference->root;
        $items = $subtree->items;
        for ($i = 1, $count = count($items); $i < $count; $i++) {
            $item = $items[$i];
            if ($item instanceof EncodedNode) {
                $nodes[$item->id] = $item;
            } else {
                $this->flatten($item, $nodes);
            }
        }
    }

    /** @param array<int, EncodedNode> $nodes */
    private function fullFrame(int $rootId, array $nodes): string
    {
        $frame = Protocol::TREE_MAGIC
            .Wire::u16(Protocol::VERSION)
            .Wire::u64($rootId)
            .Wire::u32($this->nodeCount);
        foreach ($nodes as $node) {
            $frame .= $node->bytes();
        }

        return $frame;
    }

    /**
     * Diffs the frame against the committed tree. Subtrees the last frame
     * already emitted are skipped (only their root placement can change), so
     * the cost follows what changed instead of the tree size.
     *
     * @return array{0: ?string, 1: array<int, EncodedNode>}
     */
    private function patchFrame(int $rootId, SubtreeReference $tree): array
    {
        $previousTree = $this->previousTree;

        if ($previousTree === null) {
            throw new LogicException('Cannot encode a patch without a previous tree.');
        }

        $diff = new PatchBuilder($this->committed);
        $this->walked = [];
        $this->walk($tree, $diff);
        $removals = [];
        $this->removed($previousTree, $removals);

        $committed = $this->committed;
        if (count($committed) - count($removals) + count($diff->creates) !== $this->nodeCount) {
            $this->collision($tree);
        }
        foreach ($removals as $id => $_) {
            unset($committed[$id]);
        }
        foreach ($diff->changed as $id => $node) {
            $committed[$id] = $node;
        }

        $operations = [];
        foreach ($removals as $id => $_) {
            $operations[] = "\x02".pack('P', $id);
        }
        foreach ($diff->creates as $create) {
            $operations[] = $create;
        }
        foreach ($diff->moves as $move) {
            $operations[] = $move;
        }
        foreach ($diff->updates as $update) {
            $operations[] = $update;
        }

        if ($this->previousRoot !== $rootId) {
            $operations[] = "\x05".Wire::u64($rootId);
        }

        if ($operations === []) {
            return [null, $committed];
        }

        return [
            Protocol::PATCH_MAGIC
                .Wire::u16(Protocol::VERSION)
                .Wire::u32(count($operations))
                .implode('', $operations),
            $committed,
        ];
    }

    /** Diffs one emitted subtree in frame order. */
    private function walk(SubtreeReference $reference, PatchBuilder $diff): void
    {
        $subtree = $reference->subtree;
        $root = $reference->root;
        $this->walked[$root->id] = true;
        $diff->node($root);
        $key = spl_object_id($subtree);
        if (isset($this->emitted[$key])) {
            // Emitted unchanged by the last frame: only its root can differ.
            $this->retain($subtree);

            return;
        }
        $this->emitting[$key] = $subtree;
        $items = $subtree->items;
        for ($i = 1, $count = count($items); $i < $count; $i++) {
            $item = $items[$i];
            if ($item instanceof EncodedNode) {
                $this->walked[$item->id] = true;
                $diff->node($item);
            } else {
                $this->walk($item, $diff);
            }
        }
    }

    private function retain(EncodedSubtree $subtree): void
    {
        $this->emitting[spl_object_id($subtree)] = $subtree;
        foreach ($subtree->children as $child) {
            $this->retain($child);
        }
    }

    /**
     * Ids of the last frame that this frame no longer has, in the last
     * frame's order. Subtrees this frame emits again keep all their ids.
     *
     * @param array<int, true> $removals
     */
    private function removed(SubtreeReference $reference, array &$removals): void
    {
        $subtree = $reference->subtree;
        if (isset($this->emitting[spl_object_id($subtree)])) {
            return;
        }
        $id = $reference->root->id;
        if (!isset($this->walked[$id])) {
            $removals[$id] = true;
        }
        $items = $subtree->items;
        for ($i = 1, $count = count($items); $i < $count; $i++) {
            $item = $items[$i];
            if ($item instanceof EncodedNode) {
                if (!isset($this->walked[$item->id])) {
                    $removals[$item->id] = true;
                }
            } else {
                $this->removed($item, $removals);
            }
        }
    }

    /**
     * A reused subtree shares an id with another node of the frame: reports
     * the first duplicated identity.
     */
    private function collision(SubtreeReference $tree): never
    {
        $seen = [];
        $duplicate = null;
        $visit = static function (SubtreeReference $reference) use (&$visit, &$seen, &$duplicate): void {
            foreach ([$reference->root, ...array_slice($reference->subtree->items, 1)] as $item) {
                if ($duplicate !== null) {
                    return;
                }
                if ($item instanceof SubtreeReference) {
                    $visit($item);
                } elseif (isset($seen[$item->id])) {
                    $duplicate = $item->id;
                } else {
                    $seen[$item->id] = true;
                }
            }
        };
        $visit($tree);
        $identity = $duplicate === null ? 'root|0' : ($this->idIdentities[$duplicate] ?? 'root|0');
        $path = substr($identity, 0, (int) strrpos($identity, '|'));

        throw new LogicException("Element identity collision at {$path}; assign a unique key.");
    }

    private function nodeId(string $path, ?string $domIdentity, int $kind): int
    {
        $identity = ($domIdentity === null ? $path : 'dom:'.$domIdentity)
            .'|'.$kind;
        $id = $this->identityIds[$identity]
            // Top 60 bits of the big-endian XXH3 digest (as hexdec of its
            // first 15 hex digits), read without the hex round trip.
            ??= (unpack('J', hash('xxh3', $identity, true))[1] >> 4) & 0x0FFFFFFFFFFFFFFF;

        if (
            $id === 0
            || isset($this->ids[$id])
            || (isset($this->idIdentities[$id]) && $this->idIdentities[$id] !== $identity)
        ) {
            throw new LogicException("Element identity collision at {$path}; assign a unique key.");
        }

        $this->idIdentities[$id] = $identity;
        $this->ids[$id] = true;

        return $id;
    }

    /**
     * @internal One property value in wire form (validated), as encodeNode()
     * encodes it.
     */
    public static function encodeProperty(string|int|float|bool|BinaryValue $value): string
    {
        if (is_string($value)) {
            return self::$encodedStrings[$value] ?? self::encodeString($value);
        }
        if (is_int($value)) {
            return self::$encodedIntegers[$value] ?? self::encodeInteger($value);
        }
        if (is_bool($value)) {
            return $value ? "\x04\x01" : "\x04\x00";
        }

        return self::encodeValue($value);
    }

    private static function segmentName(int $kind, string|int $slot): string
    {
        if (count(self::$segmentNames) >= 256) {
            self::$segmentNames = [];
        }
        if (count(self::$segmentNames[$kind] ?? []) >= 4096) {
            self::$segmentNames[$kind] = [];
        }

        return $kind.':'.$slot;
    }

    private static function encodeInteger(int $value): string
    {
        $encoded = "\x02".pack('P', $value);
        if (count(self::$encodedIntegers) >= 4096) {
            self::$encodedIntegers = [];
        }

        return self::$encodedIntegers[$value] = $encoded;
    }

    private static function encodeString(string $value): string
    {
        $encoded = "\x01".Wire::sized(self::validatedText($value));
        $length = strlen($value);
        if ($length <= 256) {
            if (count(self::$encodedStrings) >= 8192) {
                self::$encodedStrings = [];
                self::$encodedBytes = 0;
            }
            self::$encodedStrings[$value] = $encoded;
        } elseif ($length <= 262_144) {
            // Long strings (inline images, rich text spans) recur every
            // frame; their validation and copy are kept within a byte budget.
            if (self::$encodedBytes + $length > 16_777_216) {
                foreach (self::$encodedStrings as $cached => $_) {
                    if (strlen($cached) > 256) {
                        unset(self::$encodedStrings[$cached]);
                    }
                }
                self::$encodedBytes = 0;
            }
            self::$encodedBytes += $length;
            self::$encodedStrings[$value] = $encoded;
        }

        return $encoded;
    }

    private static function eventProperty(int $kind): int
    {
        EventKind::from($kind);

        throw new LogicException(
            'Runtime events cannot be encoded as element callbacks.',
        );
    }

    private static function encodeValue(string|int|float|bool|BinaryValue $value): string
    {
        return match (true) {
            is_string($value) => "\x01".Wire::sized(self::validatedText($value)),
            is_int($value) => "\x02".pack('P', $value),
            is_float($value) => "\x03".pack('e', self::validatedFloat($value)),
            is_bool($value) => "\x04".($value ? "\x01" : "\x00"),
            $value instanceof BinaryValue => "\x05".Wire::sized($value->bytes),
        };
    }

    private static function validatedText(string $value): string
    {
        if (preg_match('//u', $value) !== 1) {
            throw new InvalidArgumentException('Text properties must contain valid UTF-8.');
        }

        return $value;
    }

    private static function validatedFloat(float $value): float
    {
        if (!is_finite($value)) {
            throw new InvalidArgumentException('Floating properties must be finite.');
        }

        return $value;
    }
}
