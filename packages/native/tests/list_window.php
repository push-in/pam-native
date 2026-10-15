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

// A 10,000-row list ships 48 materialized rows and 9,952 childless placeholders.
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
[$listNode, $listRows] = $windowRows($nodes);
$placeholders = array_values(array_filter($listRows, $isPlaceholder));
$assert(
    $first['full'] === true
        && isset($listNode->properties[PropKey::ListWindowed->value])
        && count($listRows) === 10_000
        && count($placeholders) === 10_000 - 48 - 1
        && !$isPlaceholder($listRows[47]) && $childCount($nodes, $listRows[47]) === 2
        && $isPlaceholder($listRows[48]) && $childCount($nodes, $listRows[48]) === 0
        && !$isPlaceholder($listRows[5_000]) && $childCount($nodes, $listRows[5_000]) === 1
        && count($nodes) === 1 + 10_000 + 48 * 2 + 1
        && strlen((string) $first['frame']) < 600_000,
    'Long lists must encode a window of rows plus placeholders; sticky headers stay materialized.',
);

// Native shows rows 900-910: the window grows 64 rows (the minimum margin)
// around them, ids are stable.
$rowIds = array_map(static fn (EncodedNode $node): int => $node->id, $listRows);
$assert(
    $encoder->extendWindow($listNode->id, $rowIds[900], $rowIds[910]) === true
        && $encoder->extendWindow($listNode->id, $rowIds[902], $rowIds[908]) === false
        && $encoder->extendWindow($listNode->id, $rowIds[930], $rowIds[935]) === false
        && $encoder->extendWindow(123_456, $rowIds[1], $rowIds[2]) === false,
    'extendWindow must grow once for uncovered rows and ignore covered rows and unknown lists.',
);
$second = $encoder->encode($list);
$nodes = $encoder->frameNodes();
[, $listRows] = $windowRows($nodes);
$assert(
    $second['full'] === false && $second['frame'] !== null
        && array_map(static fn (EncodedNode $node): int => $node->id, $listRows) === $rowIds
        && !$isPlaceholder($listRows[836]) && !$isPlaceholder($listRows[974])
        && $isPlaceholder($listRows[835]) && $isPlaceholder($listRows[975])
        && $childCount($nodes, $listRows[900]) === 2
        && !$isPlaceholder($listRows[10]),
    'A grown window must patch placeholders into full rows in place (same ids), keeping earlier rows.',
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

// initialScrollIndex materializes the rows around it too.
$initialEncoder = new TreeEncoder();
$initialEncoder->encode(VirtualizedList::make(...$rows)->initialScrollIndex(7_000)->toElement());
[, $initialRows] = $windowRows($initialEncoder->frameNodes());
$assert(
    !$isPlaceholder($initialRows[0]) && !$isPlaceholder($initialRows[6_936])
        && !$isPlaceholder($initialRows[7_047]) && $isPlaceholder($initialRows[7_048])
        && $isPlaceholder($initialRows[6_935]),
    'initialScrollIndex must be inside the first window.',
);

// Filtering the rows (a new, shorter tree) keeps working on the same window.
$filtered = VirtualizedList::make(...array_slice($rows, 0, 200))->estimatedRowHeight(64)->toElement();
$filteredFrame = $encoder->encode($filtered);
[, $filteredRows] = $windowRows($encoder->frameNodes());
$assert(
    $filteredFrame['frame'] !== null && count($filteredRows) === 200
        && !$isPlaceholder($filteredRows[47]) && $isPlaceholder($filteredRows[48]),
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
[$lazyListNode, $lazyRows] = $windowRows($lazyNodes);
$lazyPlaceholders = array_values(array_filter($lazyRows, $isPlaceholder));
$assert(
    count($built) === 48
        && count($lazyRows) === 10_000
        && count($lazyPlaceholders) === 10_000 - 48
        && $lazyPlaceholders[0]->kind === NodeKind::View->value
        && $lazyPlaceholders[0]->properties === [PropKey::ListPlaceholder->value => "\x04\x01"]
        && !$isPlaceholder($lazyRows[47]) && $childCount($lazyNodes, $lazyRows[47]) === 2
        && count($lazyNodes) === 1 + 10_000 + 48 * 2,
    'VirtualizedList::of must build only the window and send keyed placeholders for the rest.',
);
$lazyIds = array_map(static fn (EncodedNode $node): int => $node->id, $lazyRows);
$assert(
    $lazyEncoder->extendWindow($lazyListNode->id, $lazyIds[5_000], $lazyIds[5_010]) === true,
    'A lazy window must grow from the placeholder ids native reports.',
);
$lazyPatch = $lazyEncoder->encode($lazyList);
[, $lazyRows] = $windowRows($lazyEncoder->frameNodes());
$assert(
    $lazyPatch['frame'] !== null
        && count($built) === 48 + (5_075 - 4_936)
        && !$isPlaceholder($lazyRows[5_000]) && $isPlaceholder($lazyRows[4_935]) && $isPlaceholder($lazyRows[5_075])
        && $lazyRows[5_000]->id !== $lazyIds[5_000],
    'Growing a lazy window must build exactly the new rows (the placeholders are replaced).',
);
$shortLazy = new TreeEncoder();
$shortLazy->encode(VirtualizedList::of([['id' => 'a'], ['id' => 'b']], static fn (array $item) => Text::make($item['id']))->toElement());
[, $shortRows] = $windowRows($shortLazy->frameNodes());
$assert(
    count($shortRows) === 2 && array_filter($shortRows, $isPlaceholder) === [],
    'Short lazy lists build every row (keys default to the item id).',
);
