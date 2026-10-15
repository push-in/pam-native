<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

// 1.45.0: `<VirtualizedList>` whose child is a p-for renders only the rows
// the window materializes, with keyed identity and component lifecycle.
if (!function_exists('pam_native_commit')) {
    function pam_native_commit(string $frame): bool
    {
        $GLOBALS['pamIdentityFrames'][] = $frame;

        return true;
    }
}
$lazyDirectory = sys_get_temp_dir().'/pam-native-lazy-template-'.getmypid();
if (!is_dir($lazyDirectory) && !mkdir($lazyDirectory, 0o755, true) && !is_dir($lazyDirectory)) {
    throw new RuntimeException('Cannot create the lazy template fixture directory.');
}
file_put_contents($lazyDirectory.'/LazyTile.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\LazyTemplate;

use Pam\Native\Component;

final class LazyTile extends Component
{
    /** @var array<string, int> */
    public static array $mounts = [];

    /** @var array<string, int> */
    public static array $unmounts = [];

    public int $taps = 0;

    public function __construct(public string $item)
    {
    }

    public function mount(): void
    {
        self::$mounts[$this->item] = (self::$mounts[$this->item] ?? 0) + 1;
    }

    public function unmount(): void
    {
        self::$unmounts[$this->item] = (self::$unmounts[$this->item] ?? 0) + 1;
    }

    public function tap(): void
    {
        $this->taps++;
        $this->emit('hit', $this->item.':'.$this->taps);
    }
}
?>

<template>
    <Pressable :accessibilityLabel="'tile:'.$item.':'.$taps" @press="tap">
        <Text>{{ $item }}</Text>
    </Pressable>
</template>
PAM);
file_put_contents($lazyDirectory.'/LazyHost.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\LazyTemplate;

use Pam\Native\Component;

final class LazyHost extends Component
{
    /** @var list<string> */
    public array $items = [];

    public string $title = 'Lazy';

    /** @var list<string> */
    public array $log = [];

    public function hit(string $value): void
    {
        $this->log[] = $value;
    }
}
?>

<template>
    <Column>
        <Text>{{ $title }}</Text>
        <VirtualizedList estimatedRowHeight="64">
            <LazyTile p-for="$item in $items" :key="$item" :item="$item" @hit="hit" />
        </VirtualizedList>
    </Column>
</template>
PAM);
App::components($lazyDirectory, $lazyDirectory.'/.cache');
$lazyHost = App::make('Pam\\Native\\Tests\\LazyTemplate\\LazyHost');
$lazyHost->items = array_map(static fn (int $i): string => "item-{$i}", range(0, 1_999));
$tileClass = 'Pam\\Native\\Tests\\LazyTemplate\\LazyTile';
$GLOBALS['pamIdentityFrames'] = [];
App::run($lazyHost);
$syncNative();

$lazyTiles = static function () use ($nativeTree): array {
    $tiles = [];
    foreach ($nativeTree->order() as $nodeId) {
        $label = $nativeTree->nodes[$nodeId]['props'][PropKey::AccessibilityLabel->value] ?? null;
        if ($label !== null && str_contains($label, 'tile:')) {
            $tiles[substr($label, strpos($label, 'tile:') + 5)] = $nodeId;
        }
    }

    return $tiles;
};
$lazyPlaceholders = static function () use ($nativeTree): array {
    $ids = [];
    foreach ($nativeTree->order() as $nodeId) {
        if (isset($nativeTree->nodes[$nodeId]['props'][PropKey::ListPlaceholder->value])) {
            $ids[] = $nodeId;
        }
    }

    return $ids;
};
$tiles = $lazyTiles();
$assert(
    count($tiles) === 48 && count($lazyPlaceholders()) === 2_000 - 48
        && count($tileClass::$mounts) === 48 && isset($tileClass::$mounts['item-47'])
        && !isset($tileClass::$mounts['item-48']),
    'A template p-for in a long VirtualizedList must render only the window (got '.count($tiles).' tiles, '
        .count($tileClass::$mounts).' mounts).',
);

// Events reach the lazily rendered component and its own state.
$pointer = \Pam\Native\Internal\Wire::map([
    'x' => 4.0, 'y' => 4.0, 'pageX' => 4.0, 'pageY' => 4.0, 'timestamp' => 1, 'pointerId' => 0,
]);
Runtime::dispatchEvent($tiles['item-3:0'], EventKind::Press->value, $pointer);
$syncNative();
$tiles = $lazyTiles();
$assert(
    $lazyHost->log === ['item-3:1'] && isset($tiles['item-3:1']),
    'A press on a lazy row must run its component and re-render it ('.implode(',', $lazyHost->log).').',
);

// The host re-renders (title change): rows keep their instances by key.
$lazyHost->title = 'Changed';
Runtime::requestRender();
Runtime::render();
$syncNative();
$tiles = $lazyTiles();
$assert(
    isset($tiles['item-3:1']) && count($tiles) === 48 && $tileClass::$unmounts === []
        && max($tileClass::$mounts) === 1,
    'Re-rendering the owner must keep lazy row components mounted with their state.',
);

// Native reports rows near placeholders: the window grows, new rows mount.
$placeholders = $lazyPlaceholders();
$listId = $nativeTree->nodes[$placeholders[0]]['parent'];
Runtime::dispatchEvent($listId, EventKind::ListWindow->value, $placeholders[950].','.$placeholders[960]);
$syncNative();
$tiles = $lazyTiles();
$assert(
    isset($tiles['item-1000:0'], $tiles['item-3:1']) && count($tiles) > 48
        && ($tileClass::$mounts['item-1000'] ?? 0) === 1 && $tileClass::$unmounts === [],
    'Growing the window of a memoized owner must mount the new rows and keep the old ones.',
);
$mountedAfterGrow = count($tileClass::$mounts);

// Scrolling far away slides the window: rows left behind unmount (their
// instances stay keyed while the owner is reused) and come back with state.
$placeholders = $lazyPlaceholders();
Runtime::dispatchEvent($listId, EventKind::ListWindow->value, $placeholders[count($placeholders) - 20].','.$placeholders[count($placeholders) - 10]);
$syncNative();
$tiles = $lazyTiles();
$assert(
    !isset($tiles['item-1000:0']) && isset($tiles['item-1990:0'], $tiles['item-3:1'])
        && ($tileClass::$unmounts['item-1000'] ?? 0) === 1 && !isset($tileClass::$unmounts['item-3']),
    'Rows the window slid away from must unmount; the head stays.',
);

// Reordering keeps identity: item-3's state follows its key.
$lazyHost->items = array_reverse($lazyHost->items);
$lazyHost->items = array_merge(['item-3'], array_values(array_diff($lazyHost->items, ['item-3'])));
Runtime::requestRender();
Runtime::render();
$syncNative();
$tiles = $lazyTiles();
$assert(
    isset($tiles['item-3:1']) && ($tileClass::$mounts['item-3'] ?? 0) === 1,
    'A reordered lazy row must keep its component state by key.',
);

// Removing items unmounts their components.
$lazyHost->items = ['item-3', 'item-4'];
Runtime::requestRender();
Runtime::render();
$syncNative();
$tiles = $lazyTiles();
$assert(
    count($tiles) === 2 && isset($tiles['item-3:1']) && ($tileClass::$unmounts['item-1990'] ?? 0) === 1
        && !isset($tileClass::$unmounts['item-3']) && $lazyPlaceholders() === [],
    'Rows that leave the list must unmount; short lists render eagerly.',
);
Runtime::shutdown();
$GLOBALS['pamIdentityFrames'] = [];

// Template touchables get the press feedback of their builder classes
// (TouchableWithoutFeedback none), on the first and the fast render path.
$touchTemplate = \Pam\Native\Internal\TemplateCompiler::compile(
    '<Column><TouchableWithoutFeedback><Text>a</Text></TouchableWithoutFeedback>'
    .'<TouchableOpacity><Text>b</Text></TouchableOpacity>'
    .'<TouchableWithoutFeedback pressedOpacity="0.5"><Text>c</Text></TouchableWithoutFeedback></Column>',
);
for ($pass = 0; $pass < 3; $pass++) {
    $touch = \Pam\Native\Internal\TemplateRenderer::render($touchTemplate, null, []);
    $opacities = array_map(
        static fn ($child) => $child->properties[PropKey::PressOpacity->value] ?? null,
        $touch->children,
    );
    $assert(
        $opacities === [1.0, 0.65, 0.5],
        'Template touchables must keep their builder press feedback (pass '.$pass.': '.json_encode($opacities).').',
    );
}
