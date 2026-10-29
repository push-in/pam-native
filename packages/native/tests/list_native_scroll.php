<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

// Native scrolling of long lists, for any app: drag and fling phases reach
// PHP only through the handlers bound to them, and #[PureHandler] phase
// handlers that change nothing commit no frame. Rows prepended to (or slid
// through) the window arrive as one incremental patch with the new rows only
// and never a positioning request: the native list keeps the first visible
// row at its pixel offset by itself.
$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};
$scrollDirectory = sys_get_temp_dir().'/pam-native-list-native-scroll-'.getmypid();
if (!is_dir($scrollDirectory) && !mkdir($scrollDirectory, 0o755, true) && !is_dir($scrollDirectory)) {
    throw new RuntimeException('Cannot create the native scroll fixture directory.');
}
file_put_contents($scrollDirectory.'/ScrollFeed.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\NativeScroll;

use Pam\Native\Attributes\PureHandler;
use Pam\Native\Component;
use Pam\Native\ScrollPhaseEvent;

final class ScrollFeed extends Component
{
    /** @var list<string> */
    public array $items = [];

    public static int $phases = 0;

    public bool $nearTop = false;

    #[PureHandler]
    public function phase(ScrollPhaseEvent $event): void
    {
        self::$phases++;
        // Low-rate paging decision: state changes only past a threshold.
        $nearTop = $event->y < 100.0;
        if ($nearTop !== $this->nearTop) {
            $this->nearTop = $nearTop;
        }
    }
}
?>

<template>
    <VirtualizedList estimatedRowHeight="64"
        on:scrollBeginDrag="phase" on:scrollEndDrag="phase" on:momentumScrollEnd="phase">
        <Text p-for="$item in $items" :key="$item">{{ $item }}</Text>
    </VirtualizedList>
</template>
PAM);
file_put_contents($scrollDirectory.'/ScrollTail.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\NativeScroll;

use Pam\Native\Component;

final class ScrollTail extends Component
{
    /** @var list<string> */
    public array $items = [];
}
?>

<template>
    <VirtualizedList estimatedRowHeight="64" initialScrollIndex="1000000">
        <View p-for="$item in $items" :key="$item"><Text>{{ $item }}</Text></View>
    </VirtualizedList>
</template>
PAM);
file_put_contents($scrollDirectory.'/ScrollTailSized.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\NativeScroll;

use Pam\Native\Component;

final class ScrollTailSized extends Component
{
    /** @var list<string> */
    public array $items = [];
}
?>

<template>
    <VirtualizedList estimatedRowHeight="64" windowSize="5" initialScrollIndex="1000000">
        <View p-for="$item in $items" :key="$item"><Text>{{ $item }}</Text></View>
    </VirtualizedList>
</template>
PAM);
file_put_contents($scrollDirectory.'/ScrollKinds.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\NativeScroll;

use Pam\Native\Component;

final class ScrollKinds extends Component
{
    /** @var list<string> */
    public array $items = [];

    public function tapped(): void
    {
    }
}
?>

<template>
    <VirtualizedList estimatedRowHeight="64" windowSize="3">
        <Pressable p-for="$item in $items" :key="$item" :accessibilityLabel="$item" on:press="tapped"><Text>{{ $item }}</Text></Pressable>
    </VirtualizedList>
</template>
PAM);
App::components($scrollDirectory, $scrollDirectory.'/.cache');
$scrollFeed = App::make('Pam\\Native\\Tests\\NativeScroll\\ScrollFeed');
$scrollFeed->items = array_map(static fn (int $i): string => "msg-{$i}", range(100, 159));
$GLOBALS['pamIdentityFrames'] = [];
App::run($scrollFeed);
$syncNative();
$scrollList = null;
foreach ($nativeTree->nodes as $nodeId => $node) {
    if ($node['kind'] === NodeKind::VirtualList->value) {
        $scrollList = $nodeId;
    }
}
$assert($scrollList !== null, 'The fixture renders a virtual list.');
$phasePayload = static fn (float $y): string => Wire::map([
    'x' => 0.0, 'y' => $y, 'velocityX' => 0.0, 'velocityY' => -4000.0, 'page' => 0,
    'contentWidth' => 360.0, 'contentHeight' => 4000.0, 'layoutWidth' => 360.0, 'layoutHeight' => 700.0,
]);

// A fling far from the paging threshold: phases run, nothing is committed.
foreach ([EventKind::ScrollBeginDrag, EventKind::ScrollEndDrag, EventKind::MomentumScrollEnd] as $phase) {
    Runtime::dispatchEvent($scrollList, $phase->value, $phasePayload(1800.0));
}
$assert(
    $scrollFeed::$phases === 3 && $GLOBALS['pamIdentityFrames'] === [],
    'Drag and fling phases of a long list must commit no frame ('.count($GLOBALS['pamIdentityFrames']).' frames).',
);
// The host's per-range window report of a list below the window threshold
// changes nothing either.
Runtime::dispatchEvent($scrollList, EventKind::ListWindow->value, '@10,20');
$assert($GLOBALS['pamIdentityFrames'] === [], 'A window report that moves no window must commit no frame.');

// Paging: an older page joins at the head as one patch with only its rows.
$before = $nativeTree->nodes;
$scrollFeed->items = [...array_map(static fn (int $i): string => "msg-{$i}", range(80, 99)), ...$scrollFeed->items];
Runtime::requestRender();
Runtime::render();
$pageFrames = $GLOBALS['pamIdentityFrames'];
$assert(
    count($pageFrames) === 1 && str_starts_with($pageFrames[0], 'PNP1') && strlen($pageFrames[0]) < 8_192,
    'An older page must be one incremental patch well under the frame budget (got '
        .count($pageFrames).' frames, '.strlen($pageFrames[0] ?? '').' bytes).',
);
$syncNative();
$created = array_diff_key($nativeTree->nodes, $before);
$removed = array_diff_key($before, $nativeTree->nodes);
$assert(
    count($removed) === 0 && count($created) === 20,
    'A prepended page creates only its own rows (created '.count($created).', removed '.count($removed).').',
);
$assert(
    !isset($nativeTree->nodes[$scrollList]['props'][PropKey::ScrollRequest->value]),
    'A prepended page never carries a positioning request.',
);

// A threshold crossing commits (the app reacts to it), once.
Runtime::dispatchEvent($scrollList, EventKind::MomentumScrollEnd->value, $phasePayload(20.0));
$assert(count($GLOBALS['pamIdentityFrames']) === 0 && $scrollFeed->nearTop, 'An unrendered threshold flag commits nothing.');
$syncNative();

// Sliding window: rows leave at the head and join at the tail in one patch.
$before = $nativeTree->nodes;
$scrollFeed->items = [...array_slice($scrollFeed->items, 16), ...array_map(static fn (int $i): string => "msg-{$i}", range(160, 175))];
Runtime::requestRender();
Runtime::render();
$slideFrames = $GLOBALS['pamIdentityFrames'];
$syncNative();
$created = array_diff_key($nativeTree->nodes, $before);
$removed = array_diff_key($before, $nativeTree->nodes);
$assert(
    count($slideFrames) === 1 && str_starts_with($slideFrames[0], 'PNP1')
        && count($created) === 16 && count($removed) === 16,
    'A window slide is one patch with only the rows that join and leave (created '
        .count($created).', removed '.count($removed).').',
);
Runtime::shutdown();
$GLOBALS['pamIdentityFrames'] = [];

// A long list opened at its end ("initialScrollIndex" past the last row, the
// way a conversation opens) materializes its newest rows, not its first ones.
$scrollTail = App::make('Pam\\Native\\Tests\\NativeScroll\\ScrollTail');
$scrollTail->items = array_map(static fn (int $i): string => "tail-{$i}", range(0, 299));
App::run($scrollTail);
$syncNative();
$tailTexts = [];
foreach ($nativeTree->nodes as $node) {
    foreach ($node['props'] as $value) {
        if (is_string($value) && str_contains($value, 'tail-')) {
            $tailTexts[] = $value;
        }
    }
}
$assert(
    (bool) array_filter($tailTexts, static fn (string $text): bool => str_contains($text, 'tail-299'))
        && !array_filter($tailTexts, static fn (string $text): bool => str_contains($text, 'tail-100')),
    'A list opened past its end must materialize its last rows (got '.count($tailTexts).' texts).',
);
Runtime::shutdown();
$GLOBALS['pamIdentityFrames'] = [];

// A row released by a `windowSize` window and built again keeps its native
// node: its placeholder takes the row's root kind, so the list never sees a
// remove + insert (which lost its anchor and the pixel position).
$scrollKinds = App::make('Pam\\Native\\Tests\\NativeScroll\\ScrollKinds');
$scrollKinds->items = array_map(static fn (int $i): string => "k-{$i}", range(0, 299));
App::run($scrollKinds);
$syncNative();
$kindList = null;
foreach ($nativeTree->nodes as $nodeId => $node) {
    if ($node['kind'] === NodeKind::VirtualList->value) {
        $kindList = $nodeId;
    }
}
$rowOf = static function (string $label) use ($nativeTree): ?int {
    foreach ($nativeTree->nodes as $nodeId => $node) {
        $value = $node['props'][PropKey::AccessibilityLabel->value] ?? null;
        if (is_string($value) && str_contains($value, $label)) {
            return $nodeId;
        }
    }

    return null;
};
$rowFive = $rowOf('k-5');
$assert($rowFive !== null && $kindList !== null, 'The first rows of the window are built.');
$rowFiveKind = $nativeTree->nodes[$rowFive]['kind'];
Runtime::dispatchEvent($kindList, EventKind::ListWindow->value, '@250,260');
$syncNative();
$assert(
    isset($nativeTree->nodes[$rowFive]) && $nativeTree->nodes[$rowFive]['kind'] === $rowFiveKind
        && isset($nativeTree->nodes[$rowFive]['props'][PropKey::ListPlaceholder->value]),
    'A released row stays the same native node (same kind), now a placeholder.',
);
Runtime::dispatchEvent($kindList, EventKind::ListWindow->value, '@0,10');
$syncNative();
$assert(
    isset($nativeTree->nodes[$rowFive]) && !isset($nativeTree->nodes[$rowFive]['props'][PropKey::ListPlaceholder->value]),
    'A row built again reuses its native node.',
);
// Rows prepended before the window (an older page): the window follows
// its rows by key, so the rows on screen stay built (were released and
// replaced by the rows now at their old indexes).
$scrollKinds->items = [...array_map(static fn (int $i): string => "older-{$i}", range(0, 49)), ...$scrollKinds->items];
Runtime::requestRender();
Runtime::render();
$pageBytes = array_sum(array_map('strlen', $GLOBALS['pamIdentityFrames']));
$syncNative();
$assert(
    $pageBytes < 16_384,
    'A page landing above the window is a small patch (its rows arrive as placeholders), got '.$pageBytes.' bytes.',
);
$assert(
    isset($nativeTree->nodes[$rowFive]) && !isset($nativeTree->nodes[$rowFive]['props'][PropKey::ListPlaceholder->value]),
    'A page prepended before the window keeps the rows on screen built.',
);
// A report the host made before the page landed (its row count is the old
// one) names other rows now: it must not move the window off the screen.
$frameCount = count($GLOBALS['pamIdentityFrames']);
Runtime::dispatchEvent($kindList, EventKind::ListWindow->value, '@250,260,300');
$syncNative();
$assert(
    isset($nativeTree->nodes[$rowFive]) && !isset($nativeTree->nodes[$rowFive]['props'][PropKey::ListPlaceholder->value]),
    'A stale window report (rows joined since) keeps the rows on screen built.',
);
Runtime::shutdown();
$GLOBALS['pamIdentityFrames'] = [];

// The same with `windowSize` (the window sized in screens).
$scrollTail = App::make('Pam\\Native\\Tests\\NativeScroll\\ScrollTailSized');
$scrollTail->items = array_map(static fn (int $i): string => "tail-{$i}", range(0, 299));
App::run($scrollTail);
$syncNative();
$tailTexts = [];
foreach ($nativeTree->nodes as $node) {
    foreach ($node['props'] as $value) {
        if (is_string($value) && str_contains($value, 'tail-')) {
            $tailTexts[] = $value;
        }
    }
}
$assert(
    (bool) array_filter($tailTexts, static fn (string $text): bool => str_contains($text, 'tail-299'))
        && !array_filter($tailTexts, static fn (string $text): bool => str_contains($text, 'tail-100')),
    'A windowSize list opened past its end must materialize its last rows (got '.count($tailTexts).' texts).',
);
Runtime::shutdown();
$GLOBALS['pamIdentityFrames'] = [];
