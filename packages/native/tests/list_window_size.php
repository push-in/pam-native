<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\Internal\Wire;
use Pam\Native\PropKey;
use Pam\Native\UI\Text;
use Pam\Native\UI\View as WindowView;
use Pam\Native\UI\VirtualizedList as WindowList;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// --- Encoder: identity maps follow the live tree ----------------------------
$encoder = new TreeEncoder();
$rows = static fn (int $count): WindowList => WindowList::make(...array_map(
    static fn (int $row): WindowView => WindowView::make(Text::make("row {$row}"))->key("row-{$row}"),
    range(1, $count),
));
$encoder->encode($rows(90)); // below the automatic window (96 rows)
$sizes = static function (TreeEncoder $encoder): array {
    $read = static fn (string $name): int => count((new ReflectionProperty(TreeEncoder::class, $name))->getValue($encoder));
    $placements = 0;
    foreach ((new ReflectionProperty(TreeEncoder::class, 'childPaths'))->getValue($encoder) as $segments) {
        $placements += count($segments);
    }

    return [$read('identityIds'), $read('idIdentities'), $read('childPaths'), $placements];
};
[$identitiesBefore, , $pathsBefore] = $sizes($encoder);
$encoder->encode($rows(10));
[$identities, $ids, $paths, $placements] = $sizes($encoder);
$assert($identitiesBefore >= 180 && $pathsBefore >= 90, 'The encoder learns the identities of a long list.');
$assert(
    $identities <= 32 && $ids <= 32 && $paths <= 12 && $placements <= 22,
    "Removed nodes leave the encoder's identity maps (identities {$identities}, ids {$ids}, paths {$paths}, placements {$placements}).",
);
$frame = $encoder->encode($rows(30))['frame'];
$assert(is_string($frame) && str_starts_with($frame, 'PNP1'), 'Rows that come back are patched in, not a full frame.');
$again = new TreeEncoder();
$again->encode($rows(30));
$assert(
    (new ReflectionProperty(TreeEncoder::class, 'identityIds'))->getValue($encoder)
        == (new ReflectionProperty(TreeEncoder::class, 'identityIds'))->getValue($again),
    'Pruned nodes get the same ids when they come back.',
);

// --- VirtualizedList windowSize (template) ------------------------------------
// One window design: rows outside the window are ListPlaceholder nodes sized
// like their row, the host reports the rows on screen (event ListWindow) and
// the window keeps (windowSize - 1) / 2 of those screens on each side.
if (!function_exists('pam_native_commit')) {
    function pam_native_commit(string $frame): bool
    {
        $GLOBALS['pamIdentityFrames'][] = $frame;

        return true;
    }
}
$GLOBALS['pamIdentityFrames'] = [];
$windowTree = new class {
    /** @var array<int, array{parent: int, index: int, kind: int, props: array<int, string>}> */
    public array $nodes = [];
    public int $root = 0;
    private string $bytes = '';
    private int $offset = 0;

    public function apply(string $frame): void
    {
        $this->bytes = $frame;
        $this->offset = 6;
        if (str_starts_with($frame, 'PNT1')) {
            $this->nodes = [];
            $this->root = $this->int('P', 8);
            for ($count = $this->int('V', 4); $count > 0; $count--) {
                $this->node();
            }

            return;
        }
        for ($count = $this->int('V', 4); $count > 0; $count--) {
            $operation = ord($this->bytes[$this->offset++]);
            if ($operation === 1) {
                $this->node();
            } elseif ($operation === 2) {
                unset($this->nodes[$this->int('P', 8)]);
            } elseif ($operation === 3) {
                $id = $this->int('P', 8);
                $key = $this->int('v', 2);
                if (ord($this->bytes[$this->offset++]) === 1) {
                    $this->nodes[$id]['props'][$key] = $this->value();
                } else {
                    unset($this->nodes[$id]['props'][$key]);
                }
            } elseif ($operation === 4) {
                $id = $this->int('P', 8);
                $this->nodes[$id]['parent'] = $this->int('P', 8);
                $this->nodes[$id]['index'] = $this->int('V', 4);
            } elseif ($operation === 5) {
                $this->root = $this->int('P', 8);
            }
        }
    }

    /** @return list<int> child ids of $id in order */
    public function children(int $id): array
    {
        $children = [];
        foreach ($this->nodes as $child => $node) {
            if ($node['parent'] === $id && $child !== $id) {
                $children[$node['index']] = $child;
            }
        }
        ksort($children);

        return array_values($children);
    }

    public function with(int $property): ?int
    {
        foreach ($this->nodes as $id => $node) {
            if (isset($node['props'][$property])) {
                return $id;
            }
        }

        return null;
    }

    private function node(): void
    {
        $id = $this->int('P', 8);
        $parent = $this->int('P', 8);
        $index = $this->int('V', 4);
        $kind = ord($this->bytes[$this->offset++]);
        $props = [];
        for ($count = $this->int('v', 2); $count > 0; $count--) {
            $key = $this->int('v', 2);
            $props[$key] = $this->value();
        }
        $this->nodes[$id] = ['parent' => $parent, 'index' => $index, 'kind' => $kind, 'props' => $props];
    }

    private function int(string $format, int $size): int
    {
        $value = unpack($format, $this->bytes, $this->offset)[1];
        $this->offset += $size;

        return $value;
    }

    private function value(): string
    {
        $start = $this->offset;
        $type = ord($this->bytes[$this->offset++]);
        $this->offset += match ($type) {
            1, 5 => 4 + unpack('V', $this->bytes, $this->offset)[1],
            2, 3 => 8,
            4 => 1,
        };

        return substr($this->bytes, $start, $this->offset - $start);
    }
};

$windowDirectory = sys_get_temp_dir().'/pam-native-list-window-size-'.getmypid();
if (!is_dir($windowDirectory) && !mkdir($windowDirectory, 0o755, true) && !is_dir($windowDirectory)) {
    throw new RuntimeException('Cannot create the list window fixture directory.');
}
file_put_contents($windowDirectory.'/WindowRow.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\ListWindow;

use Pam\Native\Component;

final class WindowRow extends Component
{
    /** @var array<int, true> */
    public static array $live = [];

    public function __construct(public int $row = 0)
    {
    }

    public function mount(): void
    {
        self::$live[$this->row] = true;
    }

    public function unmount(): void
    {
        unset(self::$live[$this->row]);
    }
}
?>

<template>
    <Column height="100" :accessibilityLabel="'row-'.$row">
        <Text>{{ 'Row '.$row }}</Text>
        <Text>{{ 'Detail '.$row }}</Text>
    </Column>
</template>
PAM);
file_put_contents($windowDirectory.'/WindowHost.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\ListWindow;

use Pam\Native\Component;
use Pam\Native\ScrollPhaseEvent;

final class WindowHost extends Component
{
    /** @var list<int> */
    public array $rows = [];

    public int $request = 0;

    /** @var list<float> */
    public array $drags = [];

    public static ?self $last = null;

    public function __construct()
    {
        $this->rows = range(0, 49);
        self::$last = $this;
    }

    #[\Pam\Native\Attributes\PureHandler]
    public function dragged(ScrollPhaseEvent $event): void
    {
        $this->drags[] = $event->y;
    }
}
?>

<template>
    <VirtualizedList windowSize="3" estimatedRowHeight="100" :scrollRequest="$request" :scrollTargetOffset="0" on:scrollEndDrag="dragged">
        <Text>Header</Text>
        <WindowRow p-for="$row in $rows" :key="'row-'.$row" :row="$row" />
    </VirtualizedList>
</template>
PAM);
// The list's owner is nested: its ancestors cache their descendants, and
// rows built later by the encoder must still count as mounted under them.
file_put_contents($windowDirectory.'/WindowShell.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\ListWindow;

use Pam\Native\Component;

final class WindowShell extends Component
{
}
?>

<template>
    <Column>
        <Text>Shell</Text>
        <WindowHost />
    </Column>
</template>
PAM);
App::components($windowDirectory, $windowDirectory.'/.cache');
$windowRow = 'Pam\\Native\\Tests\\ListWindow\\WindowRow';
App::run(App::make('Pam\\Native\\Tests\\ListWindow\\WindowShell'));
$windowHost = (new ReflectionProperty('Pam\\Native\\Tests\\ListWindow\\WindowHost', 'last'))->getValue();
$syncWindow = static function () use ($windowTree): void {
    foreach ($GLOBALS['pamIdentityFrames'] as $frame) {
        $windowTree->apply($frame);
    }
    $GLOBALS['pamIdentityFrames'] = [];
};
$syncWindow();
$live = static function () use ($windowRow): array {
    $rows = array_keys($windowRow::$live);
    sort($rows);

    return $rows;
};
$listId = $windowTree->with(PropKey::ListWindowed->value);
$assert($listId !== null, 'A windowSize list is a windowed list.');
$rowNodes = static fn (): array => array_slice($windowTree->children($listId), 1);
$assert(count($rowNodes()) === 50, 'Every row keeps a native cell: mounted rows and placeholders.');
// Before the host reports anything: a 900 pt screen and one screen of 100 pt
// rows above and below (windowSize 3); the header is always mounted.
$assert($live() === range(0, 18), 'Only the rows within the window mount (got '.implode(',', $live()).').');
$placeholder = $windowTree->nodes[$rowNodes()[30]];
$assert(
    $windowTree->children($rowNodes()[30]) === []
        && $placeholder['props'][PropKey::Height->value] === TreeEncoder::encodeProperty(100.0)
        && isset($placeholder['props'][PropKey::ListPlaceholder->value]),
    'A row outside the window is a childless placeholder with the estimated height.',
);
$assert(!isset($windowHost->drags[0]), 'The window needs no scroll phase handler.');

$layout = static fn (float $y, float $height): string => Wire::map([
    'x' => 0.0, 'y' => $y, 'width' => 390.0, 'height' => $height,
]);
// The host shows rows 1-5 (a 5-row screen): two screens around them.
$window = static function (int $first, int $last) use ($listId, $syncWindow): void {
    Runtime::dispatchEvent($listId, EventKind::ListWindow->value, "@{$first},{$last}");
    $syncWindow();
};
$window(1, 5);
$assert($live() === range(0, 9), 'The reported screen sizes the window (got '.implode(',', $live()).').');

// Far down: rows behind the window unmount and keep their measured height.
Runtime::dispatchEvent($rowNodes()[3], EventKind::Layout->value, $layout(340.0, 180.0));
$encoderSizes = static fn (): array => $sizes((new ReflectionProperty(Runtime::class, 'encoder'))->getValue());
$window(30, 34);
$assert($live() === range(24, 38), 'The window follows the rows on screen (got '.implode(',', $live()).').');
// A later pass that renders nothing (a module result) keeps the rows the
// encoder built mounted: the nested owner's ancestors cached their
// descendants before those rows joined.
$noop = Runtime::call('list.window.test', 'noop', '', static function (): void {});
Runtime::dispatchModuleResult($noop, \Pam\Native\ModuleResultStatus::Success->value, '');
$syncWindow();
$assert($live() === range(24, 38), 'Rows built after the owner rendered stay mounted in later passes (got '.implode(',', $live()).').');
$assert($windowTree->children($rowNodes()[5]) === [], 'Rows behind the window become placeholders.');
$header = $windowTree->children($listId)[0];
$assert(
    !isset($windowTree->nodes[$header]['props'][PropKey::ListPlaceholder->value]),
    'Static children around the loop (list header) stay mounted, like ListHeaderComponent.',
);
$assert(
    $windowTree->nodes[$rowNodes()[3]]['props'][PropKey::Height->value] === TreeEncoder::encodeProperty(180.0),
    'A placeholder keeps the height its row measured.',
);
[$identitiesDeep] = $encoderSizes();
$committedDeep = count((new ReflectionProperty(TreeEncoder::class, 'committed'))->getValue((new ReflectionProperty(Runtime::class, 'encoder'))->getValue()));
$assert($identitiesDeep <= $committedDeep + 4, "The encoder forgets unmounted rows ({$identitiesDeep} identities for {$committedDeep} nodes).");
// A row or two further: still within half a margin, nothing re-encoded.
$GLOBALS['pamIdentityFrames'] = [];
Runtime::dispatchEvent($listId, EventKind::ListWindow->value, '@31,35');
$assert($GLOBALS['pamIdentityFrames'] === [], 'Small scrolls inside the window send no frame.');

// Rows that come back remount with their content, at the same position.
$window(0, 4);
$assert($live() === range(0, 8), 'Scrolling back remounts the first rows (got '.implode(',', $live()).').');
$assert(count($windowTree->children($rowNodes()[2])) === 2, 'A remounted row has its content again.');

// A scroll request with a target offset mounts the rows around it at once.
$window(40, 44);
$windowHost->request++;
Runtime::requestRender();
Runtime::render();
$syncWindow();
$assert(in_array(0, $live(), true) && in_array(45, $live(), true), 'A scroll request to the top mounts the top rows before the list gets there.');

// New items are planned with the window.
$windowHost->rows = range(100, 109);
Runtime::requestRender();
Runtime::render();
$syncWindow();
$assert(count($rowNodes()) === 10, 'New rows replace the old ones.');
$window(0, 4);
$assert($live() === range(100, 108), 'New rows are planned with the window (got '.implode(',', $live()).').');
Runtime::shutdown();
$GLOBALS['pamIdentityFrames'] = [];

// --- VirtualizedList::windowSize() (builder) --------------------------------
$built = WindowList::make(...array_map(
    static fn (int $row): WindowView => WindowView::make(Text::make("row {$row}"))->key("built-{$row}"),
    range(0, 29),
))->estimatedRowHeight(300.0)->windowSize(1);
$builtEncoder = new TreeEncoder();
$builtFrame = $builtEncoder->encode($built)['frame'];
$builtTree = clone $windowTree;
$builtTree->apply($builtFrame);
$builtList = $builtTree->with(PropKey::ListWindowed->value);
$materialized = static fn (): int => count(array_filter(
    $builtTree->children($builtList),
    static fn (int $id): bool => !isset($builtTree->nodes[$id]['props'][PropKey::ListPlaceholder->value]),
));
$assert($builtList !== null && $materialized() === 4, 'windowSize(1) builds one screen of rows (got '.$materialized().').');
$assert($builtEncoder->extendWindowRows($builtList, 20, 22), 'The builder list moves its window.');
$builtTree->apply($builtEncoder->encode($built)['frame']);
$assert($materialized() === 3, 'windowSize(1) keeps only the rows on screen.');
$shown = $built->lazyRows->shown();
$assert(
    count($shown) === 30 && $shown[21]->children() !== [] && $shown[0]->children() === [],
    'LazyRows::shown() lists the rows as sent: built rows and placeholders.',
);
$fresh = WindowList::make(...array_map(
    static fn (int $row): WindowView => WindowView::make(Text::make("fresh {$row}"))->key("fresh-{$row}"),
    range(0, 199),
));
$freshShown = $fresh->lazyRows === null ? $fresh->children() : $fresh->lazyRows->shown();
$assert(count($freshShown) === 200, 'An eager long list keeps its children.');
// A released fling names the rows it sweeps and lands on: one move builds
// them all, nothing is released while it moves, and the next move that must
// build rows releases what is behind in the same frame.
$fling = \Pam\Native\Internal\ListWindow::for(new stdClass(), 'fling');
$fling->configure(5);
$assert($fling->extend(10, 13), 'The first report plans the window.');
$assert($fling->state()[0] === [[2, 22]], 'Two screens each side (got '.json_encode($fling->state()[0]).').');
$assert($fling->extend(10, 13, null, [10, 40]), 'A fling path moves the window once.');
$assert($fling->state()[0] === [[2, 49]], 'The path and its landing screen are built at once (got '.json_encode($fling->state()[0]).').');
$moved = 0;
for ($row = 11; $row <= 36; $row++) {
    $moved += $fling->extend($row, $row + 3, null, [$row, 40]) ? 1 : 0;
}
$assert($moved === 0 && $fling->state()[0] === [[2, 49]], 'The fling crosses built rows without moving the window.');
$assert(!$fling->extend(38, 41), 'A drag over the rows a fling built releases nothing (no frame).');
$assert($fling->extend(45, 48, null, [45, 80]), 'The next fling past the window moves it.');
$assert($fling->state()[0] === [[37, 89]], 'Rows behind are released in the move that builds the next path (got '.json_encode($fling->state()[0]).').');
$assert($fling->extend(84, 87), 'A drag past the path moves the window to its screens.');
$assert($fling->state()[0] === [[76, 96]], 'Back to two screens each side (got '.json_encode($fling->state()[0]).').');
$assert($fling->extend(84, 87, null, [84, 400]), 'A path longer than ten screens is clipped.');
$assert($fling->state()[0] === [[76, 96], [353, 409]], 'A long path keeps the rows on screen and the side it lands on (got '.json_encode($fling->state()[0]).').');
$assert(!$fling->extend(100, 103, null, [100, 400]), 'The same fling over its placeholders asks nothing more.');
$kept = \Pam\Native\Internal\ListWindow::for(new stdClass(), 'fling-kept');
$kept->configure(11);
$kept->extend(30, 33);
[[$keptFrom]] = $kept->state()[0];
$assert($kept->extend(34, 37, null, [34, 70]) && $kept->state()[0][0][0] === $keptFrom, 'A wide window keeps the rows already built when a path builds more (got '.json_encode($kept->state()[0]).').');
$narrow = \Pam\Native\Internal\ListWindow::for(new stdClass(), 'fling-narrow');
$narrow->configure(5);
$narrow->extend(10, 13);
$assert($narrow->extend(14, 17, null, [14, 30]) && $narrow->state()[0] === [[6, 39]], 'A narrow window releases what is behind the path in the same frame (got '.json_encode($narrow->state()[0]).').');
echo "LIST_WINDOW_SIZE_OK rows=50 window=3 heights=measured encoder=pruned builder=1 fling=path\n";
