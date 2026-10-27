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
$encoder->encode($rows(200));
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
$assert($identitiesBefore >= 400 && $pathsBefore >= 200, 'The encoder learns the identities of a long list.');
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

// --- Template VirtualizedList windowSize --------------------------------------
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

$windowDirectory = sys_get_temp_dir().'/pam-native-list-window-'.getmypid();
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

    public function __construct()
    {
        $this->rows = range(0, 49);
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
App::components($windowDirectory, $windowDirectory.'/.cache');
$windowRow = 'Pam\\Native\\Tests\\ListWindow\\WindowRow';
$windowHost = App::make('Pam\\Native\\Tests\\ListWindow\\WindowHost');
App::run($windowHost);
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
$listId = $windowTree->with(PropKey::OnScrollBeginDrag->value);
$assert($listId !== null, 'A windowed list listens to its scroll phases.');
$rowNodes = static fn (): array => array_slice($windowTree->children($listId), 1);
$assert(count($rowNodes()) === 50, 'Every row keeps a native cell: mounted rows and placeholders.');
// Default viewport (900) and windowSize 3: one screen above and below.
$assert($live() === range(0, 18), 'Only the rows within the window mount (got '.implode(',', $live()).').');
$placeholder = $windowTree->nodes[$rowNodes()[30]];
$assert(
    $windowTree->children($rowNodes()[30]) === []
        && $placeholder['props'][PropKey::Height->value] === TreeEncoder::encodeProperty(100.0),
    'A row outside the window is a childless placeholder with the estimated height.',
);

$phase = static fn (float $y, float $velocity = 0.0): string => Wire::map([
    'x' => 0.0, 'y' => $y, 'velocityX' => 0.0, 'velocityY' => $velocity, 'page' => 0,
]);
$layout = static fn (float $y, float $height): string => Wire::map([
    'x' => 0.0, 'y' => $y, 'width' => 390.0, 'height' => $height,
]);
// The list reports its viewport; the first row its offset (below the header).
Runtime::dispatchEvent($listId, EventKind::Layout->value, $layout(0.0, 500.0));
Runtime::dispatchEvent($rowNodes()[0], EventKind::Layout->value, $layout(40.0, 100.0));
$syncWindow();
$assert($live() === range(0, 10), 'The measured viewport narrows the window (got '.implode(',', $live()).').');

// A drag far down: the rows above leave, their components unmount.
$encoderSizes = static fn (): array => $sizes((new ReflectionProperty(Runtime::class, 'encoder'))->getValue());
Runtime::dispatchEvent($listId, EventKind::ScrollBeginDrag->value, $phase(3000.0));
$syncWindow();
$assert($live() === range(24, 39), 'The window follows a drag (got '.implode(',', $live()).').');
$row24 = $rowNodes()[24];
Runtime::dispatchEvent($row24, EventKind::Layout->value, $layout(2440.0, 180.0));
$assert($windowTree->children($rowNodes()[5]) === [], 'Rows behind the window become placeholders.');
[$identitiesDeep] = $encoderSizes();
$committedDeep = count((new ReflectionProperty(TreeEncoder::class, 'committed'))->getValue((new ReflectionProperty(Runtime::class, 'encoder'))->getValue()));
$assert($identitiesDeep <= $committedDeep + 4, "The encoder forgets unmounted rows ({$identitiesDeep} identities for {$committedDeep} nodes).");

// The release velocity materializes the whole fling at once; the app's own
// handler still runs.
Runtime::dispatchEvent($listId, EventKind::ScrollEndDrag->value, $phase(3000.0, 2500.0));
$syncWindow();
$assert($live() === range(24, 49), 'A fling mounts every row up to its landing (got '.implode(',', $live()).').');
$assert($windowHost->drags === [3000.0], "The template's scrollEndDrag handler still receives the phase.");
Runtime::dispatchEvent($listId, EventKind::MomentumScrollEnd->value, $phase(4500.0));
$syncWindow();
$assert($live() === range(38, 49), 'The window shrinks to the landing (got '.implode(',', $live()).').');
$placeholder = $windowTree->nodes[$rowNodes()[24]];
$assert(
    $placeholder['props'][PropKey::Height->value] === TreeEncoder::encodeProperty(180.0),
    'A placeholder keeps the height its row measured.',
);

// Rows that come back remount with their content, at the same position.
Runtime::dispatchEvent($listId, EventKind::ScrollBeginDrag->value, $phase(0.0));
$syncWindow();
$assert($live() === range(0, 9), 'Scrolling back remounts the first rows (got '.implode(',', $live()).').');
$assert(count($windowTree->children($rowNodes()[0])) === 2, 'A remounted row has its content again.');

// A scroll request with a target offset mounts the rows around it at once.
Runtime::dispatchEvent($listId, EventKind::MomentumScrollEnd->value, $phase(4500.0));
$windowHost->request++;
Runtime::requestRender();
Runtime::render();
$syncWindow();
$assert(in_array(0, $live(), true) && in_array(45, $live(), true), 'A scroll request to the top mounts the top rows before the list gets there.');

// Removed items drop their measurements; the window plans the new rows.
$windowHost->rows = range(100, 109);
Runtime::requestRender();
Runtime::render();
$syncWindow();
$assert(count($rowNodes()) === 10 && $live() === range(100, 109), 'New rows are planned with the window.');
Runtime::shutdown();
$GLOBALS['pamIdentityFrames'] = [];
echo "LIST_WINDOW_OK rows=50 window=3 fling=landing encoder=pruned\n";
