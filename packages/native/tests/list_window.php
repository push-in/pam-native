<?php

declare(strict_types=1);

use Pam\Native\EventKind;
use Pam\Native\Internal\EncodedNode;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\UI\Column;
use Pam\Native\UI\Text;
use Pam\Native\UI\VirtualizedList;

// 1.44.0: windowed materialization of long virtualized lists.
$assert(
    PropKey::ListPlaceholder->value === 541 && PropKey::ListWindowed->value === 542
        && PropKey::ListPlaceholderRows->value === 543
        && EventKind::ListWindow->value === 80,
    'List window protocol ids must be appended in order.',
);

$windowRow = static fn (int $i) => Column::make(Text::make("Row {$i}"), Text::make("Detail {$i}"))->key("row-{$i}");

/**
 * @param array<int, EncodedNode> $nodes
 * @return array{0: EncodedNode, 1: list<EncodedNode>}
 */
$windowRows = static function (array $nodes): array {
    $list = null;
    foreach ($nodes as $node) {
        if ($node->kind === NodeKind::VirtualList->value) {
            $list = $node;
        }
    }
    \assert($list instanceof EncodedNode);
    $rows = array_values(array_filter($nodes, static fn (EncodedNode $node): bool => $node->parent === $list->id));
    usort($rows, static fn (EncodedNode $a, EncodedNode $b): int => $a->index <=> $b->index);

    return [$list, $rows];
};
$isPlaceholder = static fn (EncodedNode $node): bool => isset($node->properties[PropKey::ListPlaceholder->value]);
$childCount = static function (array $nodes, EncodedNode $row): int {
    return count(array_filter($nodes, static fn (EncodedNode $node): bool => $node->parent === $row->id));
};
/** Rows a list child stands for (a placeholder run covers several). */
$rowsOf = static function (EncodedNode $node): int {
    $rows = $node->properties[PropKey::ListPlaceholderRows->value] ?? null;

    return $rows === null ? 1 : unpack('P', substr($rows, 1, 8))[1];
};
/**
 * The list child covering each row index.
 *
 * @param list<EncodedNode> $children sorted by index
 * @return array<int, EncodedNode>
 */
$byRow = static function (array $children) use ($rowsOf): array {
    $rows = [];
    foreach ($children as $child) {
        for ($row = $child->index, $end = $child->index + $rowsOf($child); $row < $end; $row++) {
            $rows[$row] = $child;
        }
    }

    return $rows;
};

// A 10,000-row list ships 48 materialized rows and one placeholder node per
// run of rows outside the window (sized rows x the estimate by the engine).
$rows = [];
for ($i = 0; $i < 10_000; $i++) {
    $rows[] = $windowRow($i);
}
$sticky = Column::make(Text::make('Pinned'))->key('pinned')->stickyHeader();
$rows[5_000] = $sticky;
$list = VirtualizedList::make(...$rows)->estimatedRowHeight(64)->toElement();
$encoder = new TreeEncoder();
$first = $encoder->encode($list);
$nodes = $encoder->frameNodes();
[$listNode, $listChildren] = $windowRows($nodes);
$listRows = $byRow($listChildren);
$runs = array_values(array_filter($listChildren, $isPlaceholder));
$assert(
    $first['full'] === true
        && isset($listNode->properties[PropKey::ListWindowed->value])
        && count($listRows) === 10_000
        && count($listChildren) === 48 + 1 + 2
        && count($runs) === 2 && $runs[0]->index === 48 && $rowsOf($runs[0]) === 4_952
        && $runs[1]->index === 5_001 && $rowsOf($runs[1]) === 4_999
        && $runs[0]->kind === NodeKind::View->value && $childCount($nodes, $runs[0]) === 0
        && !$isPlaceholder($listRows[47]) && $childCount($nodes, $listRows[47]) === 2
        && $isPlaceholder($listRows[48])
        && !$isPlaceholder($listRows[5_000]) && $childCount($nodes, $listRows[5_000]) === 1
        && count($nodes) === 1 + 51 + 48 * 2 + 1
        && strlen((string) $first['frame']) < 20_000,
    'Long lists must encode a window of rows plus placeholder runs; sticky headers stay materialized.',
);

// Native shows rows 900-910 (inside a run, reported by row index): the
// window grows 64 rows (the minimum margin) around them.
$assert(
    $encoder->extendWindowRows($listNode->id, 900, 910) === true
        && $encoder->extendWindowRows($listNode->id, 902, 908) === false
        && $encoder->extendWindow($listNode->id, $listRows[10]->id, $listRows[12]->id) === false
        && $encoder->extendWindowRows(123_456, 1, 2) === false,
    'extendWindow must grow once for uncovered rows and ignore covered rows and unknown lists.',
);
$second = $encoder->encode($list);
$nodes = $encoder->frameNodes();
[, $listChildren] = $windowRows($nodes);
$listRows = $byRow($listChildren);
$assert(
    $second['full'] === false && $second['frame'] !== null && count($listRows) === 10_000
        && !$isPlaceholder($listRows[836]) && !$isPlaceholder($listRows[974])
        && $isPlaceholder($listRows[835]) && $isPlaceholder($listRows[975])
        && $listRows[835] !== $listRows[975]
        && $childCount($nodes, $listRows[900]) === 2
        && !$isPlaceholder($listRows[10]) && $isPlaceholder($listRows[300])
        && count($listChildren) === 48 + (975 - 836) + 1 + 3,
    'A grown window must split the run around the new rows, keeping the head.',
);
$assert($encoder->encode($list)['frame'] === null, 'An unchanged list and window must not produce a frame.');

// Short and horizontal lists are encoded completely, as before.
$short = [];
for ($i = 0; $i < 96; $i++) {
    $short[] = $windowRow($i);
}
$shortEncoder = new TreeEncoder();
$shortEncoder->encode(VirtualizedList::make(...$short)->toElement());
[$shortList, $shortRows] = $windowRows($shortEncoder->frameNodes());
$horizontalEncoder = new TreeEncoder();
$horizontalEncoder->encode(VirtualizedList::make(...$rows)->horizontal()->toElement());
[$horizontalList, $horizontalRows] = $windowRows($horizontalEncoder->frameNodes());
$assert(
    !isset($shortList->properties[PropKey::ListWindowed->value])
        && array_filter($shortRows, $isPlaceholder) === []
        && !isset($horizontalList->properties[PropKey::ListWindowed->value])
        && array_filter($horizontalRows, $isPlaceholder) === [],
    'Lists up to 96 rows and horizontal lists must not be windowed.',
);
// Each encoder keeps the identity bookkeeping of its whole tree (about 1.7 KB
// per node: a fully encoded 10,000-row horizontal list holds ~50 MB). That is
// bounded by the live tree, not a leak: releasing the encoder frees it at
// once, without the cycle collector. Release each one after its assertions so
// this file stays far below the 128M memory_limit inside run.php.
$horizontalRef = WeakReference::create($horizontalEncoder);
$memoryBeforeRelease = memory_get_usage();
unset($shortEncoder, $shortList, $shortRows, $horizontalEncoder, $horizontalList, $horizontalRows);
$assert(
    $horizontalRef->get() === null && memory_get_usage() < $memoryBeforeRelease - 20 * 1024 * 1024,
    'A released encoder must free its tree immediately (no static or cyclic retention).',
);

// Grids keep one placeholder per cell (runs would break the column flow).
$gridEncoder = new TreeEncoder();
$gridEncoder->encode(VirtualizedList::make(...$rows)->columns(3)->toElement());
[, $gridChildren] = $windowRows($gridEncoder->frameNodes());
$assert(
    count($gridChildren) === 10_000 && count(array_filter($gridChildren, $isPlaceholder)) === 10_000 - 48 - 1,
    'Multi-column lists must keep one placeholder per cell.',
);
unset($gridEncoder, $gridChildren);

// initialScrollIndex materializes the rows around it too.
$initialEncoder = new TreeEncoder();
$initialEncoder->encode(VirtualizedList::make(...$rows)->initialScrollIndex(7_000)->toElement());
[, $initialChildren] = $windowRows($initialEncoder->frameNodes());
$initialRows = $byRow($initialChildren);
$assert(
    !$isPlaceholder($initialRows[0]) && !$isPlaceholder($initialRows[6_936])
        && !$isPlaceholder($initialRows[7_047]) && $isPlaceholder($initialRows[7_048])
        && $isPlaceholder($initialRows[6_935]),
    'initialScrollIndex must be inside the first window.',
);
unset($initialEncoder, $initialChildren, $initialRows);

// Filtering the rows (a new, shorter tree) keeps working on the same window.
$filtered = VirtualizedList::make(...array_slice($rows, 0, 200))->estimatedRowHeight(64)->toElement();
$filteredFrame = $encoder->encode($filtered);
[, $filteredChildren] = $windowRows($encoder->frameNodes());
$filteredRows = $byRow($filteredChildren);
$assert(
    $filteredFrame['frame'] !== null && count($filteredRows) === 200
        && !$isPlaceholder($filteredRows[47]) && $isPlaceholder($filteredRows[48])
        && $rowsOf($filteredRows[48]) === 152,
    'A changed row set must keep the window and patch rows in place.',
);

// Data-driven lists (VirtualizedList::of): only the window is ever built.
$built = [];
$lazyList = VirtualizedList::of(
    range(0, 9_999),
    static function (int $item, int $index) use (&$built, $windowRow) {
        $built[$index] = true;

        return $windowRow($item);
    },
    static fn (int $item): string => "item-{$item}",
)->estimatedRowHeight(64)->toElement();
$lazyEncoder = new TreeEncoder();
$lazyFrame = $lazyEncoder->encode($lazyList);
$lazyNodes = $lazyEncoder->frameNodes();
[$lazyListNode, $lazyChildren] = $windowRows($lazyNodes);
$lazyRows = $byRow($lazyChildren);
$assert(
    count($built) === 48
        && count($lazyRows) === 10_000 && count($lazyChildren) === 49
        && $lazyRows[48]->kind === NodeKind::View->value
        && $lazyRows[48]->properties === [
            PropKey::ListPlaceholder->value => "\x04\x01",
            PropKey::ListPlaceholderRows->value => "\x02".pack('P', 9_952),
        ]
        && !$isPlaceholder($lazyRows[47]) && $childCount($lazyNodes, $lazyRows[47]) === 2
        && count($lazyNodes) === 1 + 49 + 48 * 2,
    'VirtualizedList::of must build only the window and send one placeholder run for the rest.',
);
$assert(
    $lazyEncoder->extendWindowRows($lazyListNode->id, 5_000, 5_010) === true,
    'A lazy window must grow from the rows native reports.',
);
$lazyPatch = $lazyEncoder->encode($lazyList);
[, $lazyChildren] = $windowRows($lazyEncoder->frameNodes());
$lazyRows = $byRow($lazyChildren);
$assert(
    $lazyPatch['frame'] !== null
        && count($built) === 48 + (5_075 - 4_936)
        && !$isPlaceholder($lazyRows[5_000]) && $isPlaceholder($lazyRows[4_935]) && $isPlaceholder($lazyRows[5_075])
        && count($lazyRows) === 10_000,
    'Growing a lazy window must build exactly the new rows.',
);
// The window slides: rows far from the visible ones return to placeholders.
$assert(
    $lazyEncoder->extendWindowRows($lazyListNode->id, 9_000, 9_010) === true,
    'A lazy window must grow far down the list.',
);
$lazyEncoder->encode($lazyList);
[, $lazyChildren] = $windowRows($lazyEncoder->frameNodes());
$lazyRows = $byRow($lazyChildren);
$materialized = array_keys(array_filter($lazyRows, static fn (EncodedNode $node): bool => !$isPlaceholder($node)));
$assert(
    !$isPlaceholder($lazyRows[0]) && $isPlaceholder($lazyRows[100]) && $isPlaceholder($lazyRows[5_000]) && !$isPlaceholder($lazyRows[9_005])
        && count($lazyRows) === 10_000 && min(array_filter($materialized, static fn (int $row): bool => $row >= 48)) >= 9_000 - 64 * 4 && max($materialized) < 9_010 + 64 * 4 + 1,
    'Rows far from the visible ones must go back to placeholders ('.count($materialized).' materialized).',
);
$shortLazy = new TreeEncoder();
$shortLazy->encode(VirtualizedList::of([['id' => 'a'], ['id' => 'b']], static fn (array $item) => Text::make($item['id']))->toElement());
[, $shortRows] = $windowRows($shortLazy->frameNodes());
$assert(
    count($shortRows) === 2 && array_filter($shortRows, $isPlaceholder) === [],
    'Short lazy lists build every row (keys default to the item id).',
);

// VirtualizedList::make() with closure rows (or a generator of them) defers
// them like ::of(): only the window is built, rows keyed by position.
$closureBuilt = 0;
$closureRows = [];
for ($i = 0; $i < 5_000; $i++) {
    $closureRows[] = static function () use ($i, &$closureBuilt, $windowRow) {
        $closureBuilt++;

        return $windowRow($i);
    };
}
$closureEncoder = new TreeEncoder();
$closureEncoder->encode(VirtualizedList::make(...$closureRows)->estimatedRowHeight(64)->toElement());
[, $closureListRows] = $windowRows($closureEncoder->frameNodes());
$assert(
    $closureBuilt === 48 && count($byRow($closureListRows)) === 5_000
        && count(array_filter($closureListRows, $isPlaceholder)) === 1,
    "Closure rows must be built only inside the window (built {$closureBuilt}).",
);
$closureBuilt = 0;
$generator = (static function () use ($closureRows) {
    yield from $closureRows;
})();
$generatorEncoder = new TreeEncoder();
$generatorEncoder->encode(VirtualizedList::make($generator)->toElement());
$assert($closureBuilt === 48, "A generator of closure rows must be deferred too (built {$closureBuilt}).");
unset($encoder, $lazyEncoder, $closureEncoder, $generatorEncoder, $shortLazy, $rows, $list, $lazyList, $closureRows, $generator, $nodes, $listRows, $lazyRows, $lazyNodes, $listChildren, $lazyChildren, $filteredChildren, $filteredRows, $sticky, $built);
