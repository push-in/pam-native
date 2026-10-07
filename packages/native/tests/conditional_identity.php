<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\Runtime;
use Pam\Native\PropKey;
use Pam\Native\Internal\DevWarnings;
use Pam\Native\Internal\EncodedNode;
use Pam\Native\Internal\TemplateCompiler;
use Pam\Native\Internal\TemplateRenderer;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\NodeKind;
use Pam\Native\TemplateRegistry;
use Pam\Native\UI\Column;
use Pam\Native\UI\Input;
use Pam\Native\UI\Modal;
use Pam\Native\UI\Text;
use Pam\Native\UI\View as NativeView;
use Pam\Native\UI\VirtualizedList;

// Device path: p-for of components, events resolved by the native node ids
// carried in the committed frames (not by calling PHP callbacks directly).
if (!function_exists('pam_native_commit')) {
    function pam_native_commit(string $frame): bool
    {
        $GLOBALS['pamIdentityFrames'][] = $frame;

        return true;
    }
}
$GLOBALS['pamIdentityFrames'] = [];
$nativeTree = new class {
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
            } else {
                throw new RuntimeException("Unknown patch operation {$operation}.");
            }
        }
    }

    /** @return list<int> node ids in native tree order */
    public function order(?int $id = null): array
    {
        $id ??= $this->root;
        $children = [];
        foreach ($this->nodes as $child => $node) {
            if ($node['parent'] === $id && $child !== $id) {
                $children[$node['index']] = $child;
            }
        }
        ksort($children);
        $ordered = [$id];
        foreach ($children as $child) {
            array_push($ordered, ...$this->order($child));
        }

        return $ordered;
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
};
$loopDirectory = sys_get_temp_dir().'/pam-native-loop-identity-'.getmypid();
if (!is_dir($loopDirectory) && !mkdir($loopDirectory, 0o755, true) && !is_dir($loopDirectory)) {
    throw new RuntimeException('Cannot create the loop identity fixture directory.');
}
file_put_contents($loopDirectory.'/LoopIdentityTile.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\LoopIdentity;

use Pam\Native\Component;

final class LoopIdentityTile extends Component
{
    public function __construct(public string $action, public string $group)
    {
    }

    public function activate(): void
    {
        $this->emit('activate', $this->group.':'.$this->action.':'.spl_object_id($this));
    }
}
?>

<template>
    <Pressable :accessibilityLabel="$group.':'.$action" @press="activate">
        <Text>{{ $action }}</Text>
    </Pressable>
</template>
PAM);
file_put_contents($loopDirectory.'/LoopIdentityHost.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\LoopIdentity;

use Pam\Native\Component;

final class LoopIdentityHost extends Component
{
    /** @var list<string> */
    public array $actions = ['reply', 'forward', 'readers', 'copy', 'react'];

    public bool $banner = false;

    /** @var list<string> */
    public array $log = [];

    public function ran(string $action): void
    {
        $this->log[] = $action;
    }
}
?>

<template>
    <Column>
        <Text p-if="$banner">Banner</Text>
        <Column>
            <LoopIdentityTile p-for="$action in $actions" :key="$action" :action="$action" group="key" @activate="ran" />
        </Column>
        <Column>
            <LoopIdentityTile p-for="$action in $actions" p-key="$action" :action="$action" group="pkey" @activate="ran" />
        </Column>
        <Column>
            <LoopIdentityTile p-for="$action in $actions" :action="$action" group="none" @activate="ran" />
        </Column>
    </Column>
</template>
PAM);
App::components($loopDirectory, $loopDirectory.'/.cache');
$loopHost = App::make('Pam\\Native\\Tests\\LoopIdentity\\LoopIdentityHost');
App::run($loopHost);
$syncNative = static function () use ($nativeTree): void {
    foreach ($GLOBALS['pamIdentityFrames'] as $frame) {
        $nativeTree->apply($frame);
    }
    $GLOBALS['pamIdentityFrames'] = [];
};
$syncNative();
$pointer = \Pam\Native\Internal\Wire::map([
    'x' => 4.0, 'y' => 4.0, 'pageX' => 4.0, 'pageY' => 4.0, 'timestamp' => 1, 'pointerId' => 0,
]);
foreach ([
    ['reply', 'forward', 'readers', 'copy', 'react'],
    ['copy', 'react', 'reply'],
    ['react', 'reply', 'forward', 'readers', 'copy', 'star'],
] as $round => $actions) {
    $loopHost->actions = $actions;
    $loopHost->banner = $round === 1;
    Runtime::requestRender();
    Runtime::render();
    $syncNative();
    $tiles = [];
    foreach ($nativeTree->order() as $nodeId) {
        $props = $nativeTree->nodes[$nodeId]['props'];
        if (isset($props[PropKey::OnPress->value], $props[PropKey::AccessibilityLabel->value])) {
            $tiles[$nodeId] = substr($props[PropKey::AccessibilityLabel->value], 5);
        }
    }
    $assert(count($tiles) === 3 * count($actions), 'Every loop iteration must mount its own native pressable.');
    $instances = [];
    foreach ($tiles as $nodeId => $label) {
        $loopHost->log = [];
        Runtime::dispatchEvent($nodeId, EventKind::Press->value, $pointer);
        $syncNative();
        [$group, $action, $instance] = explode(':', $loopHost->log[0] ?? '::');
        $assert(
            count($loopHost->log) === 1 && $group.':'.$action === $label,
            "Native press on {$label} must run its own iteration's callback (got ".implode(',', $loopHost->log).').',
        );
        $instances[$group][$instance] = true;
    }
    foreach (['key', 'pkey', 'none'] as $group) {
        $assert(
            count($instances[$group] ?? []) === count($actions),
            "p-for iterations ({$group}) must get distinct component instances.",
        );
    }
}
Runtime::shutdown();
$GLOBALS['pamIdentityFrames'] = [];

// Unkeyed siblings are identified by their static slot, not their output
// index: a conditional sibling that renders nothing leaves a hole.

/** @return array<int, EncodedNode> */
$encodedNodes = static function (TreeEncoder $encoder, Element $tree): array {
    $encoder->encode($tree);
    $nodes = $encoder->frameNodes();

    return $nodes;
};
/** @return list<int> */
$idsOf = static function (array $nodes, NodeKind $kind): array {
    $ids = [];
    foreach ($nodes as $node) {
        if ($node->kind === $kind->value) {
            $ids[] = $node->id;
        }
    }

    return $ids;
};

$chatTemplate = TemplateCompiler::compile(
    '<Column>'
    .'<Modal p-if="$overlay" presentation="fullScreen"><View /></Modal>'
    .'<Text p-if="$banner">Offline</Text>'
    .'<If condition="$hint"><Text>Hint</Text><Text>More</Text></If>'
    .'<VirtualizedList><Text>One</Text><Text>Two</Text></VirtualizedList>'
    .'<Input value="draft" />'
    .'<Modal p-if="$sheet" presentation="sheet"><View /></Modal>'
    .'</Column>',
);
$states = [
    ['overlay' => false, 'banner' => false, 'hint' => false, 'sheet' => false],
    ['overlay' => true, 'banner' => false, 'hint' => false, 'sheet' => false],
    ['overlay' => true, 'banner' => true, 'hint' => true, 'sheet' => false],
    ['overlay' => false, 'banner' => true, 'hint' => false, 'sheet' => true],
    ['overlay' => false, 'banner' => false, 'hint' => true, 'sheet' => true],
    ['overlay' => false, 'banner' => false, 'hint' => false, 'sheet' => false],
];
$encoder = new TreeEncoder();
$listIds = [];
$inputIds = [];
$overlayId = null;
$sheetId = null;
foreach ($states as $state) {
    $nodes = $encodedNodes($encoder, TemplateRenderer::render($chatTemplate, null, $state));
    $listIds[] = $idsOf($nodes, NodeKind::VirtualList)[0];
    $inputIds[] = $idsOf($nodes, NodeKind::Input)[0];
    $modals = $idsOf($nodes, NodeKind::Modal);
    if ($state['overlay'] && !$state['sheet']) {
        $overlayId = $modals[0];
    }
    if ($state['sheet'] && !$state['overlay']) {
        $sheetId = $modals[0];
    }
}
$assert(
    count(array_unique($listIds)) === 1 && count(array_unique($inputIds)) === 1,
    'Toggling p-if/If siblings before a VirtualizedList or Input must never change their native identity.',
);
$assert(
    $overlayId !== null && $sheetId !== null && $overlayId !== $sheetId,
    'A sheet Modal must not inherit the native host of a removed overlay Modal.',
);

// The same swap within one patch removes the overlay host and creates the
// sheet host instead of reusing the overlay's (already open) dialog.
$swapEncoder = new TreeEncoder();
$before = $encodedNodes($swapEncoder, TemplateRenderer::render(
    $chatTemplate,
    null,
    ['overlay' => true, 'banner' => false, 'hint' => false, 'sheet' => false],
));
$after = $encodedNodes($swapEncoder, TemplateRenderer::render(
    $chatTemplate,
    null,
    ['overlay' => false, 'banner' => true, 'hint' => false, 'sheet' => true],
));
$assert(
    array_intersect($idsOf($before, NodeKind::Modal), $idsOf($after, NodeKind::Modal)) === []
        && $idsOf($before, NodeKind::VirtualList) === $idsOf($after, NodeKind::VirtualList)
        && $idsOf($before, NodeKind::Input) === $idsOf($after, NodeKind::Input),
    'Swapping an overlay Modal for a sheet Modal must keep distinct hosts and stable followers.',
);

// p-if / p-else chains and Show blocks keep the following siblings stable.
$elseTemplate = TemplateCompiler::compile(
    '<Column><Text p-if="$a">A</Text><Text p-else>B</Text>'
    .'<Show when="$b"><View /></Show><Input value="x" /></Column>',
);
$elseIds = [];
foreach ([[true, false], [false, true], [true, true], [false, false]] as [$a, $b]) {
    $nodes = $encodedNodes(new TreeEncoder(), TemplateRenderer::render($elseTemplate, null, ['a' => $a, 'b' => $b]));
    $elseIds[] = $idsOf($nodes, NodeKind::Input)[0];
}
$assert(
    count(array_unique($elseIds)) === 1,
    'p-if/p-else branches and Show blocks must not shift the identity of later siblings.',
);
$branchA = $encodedNodes(new TreeEncoder(), TemplateRenderer::render($elseTemplate, null, ['a' => true, 'b' => false]));
$branchB = $encodedNodes(new TreeEncoder(), TemplateRenderer::render($elseTemplate, null, ['a' => false, 'b' => false]));
$assert(
    $idsOf($branchA, NodeKind::Text) !== $idsOf($branchB, NodeKind::Text),
    'p-if and p-else branches occupy distinct template slots.',
);

// Element API: null and false children are holes.
$apiTree = static fn (bool $overlay, bool $banner): Element => Column::make(
    $overlay ? Modal::make(NativeView::make()) : null,
    $banner ? Text::make('Offline') : false,
    VirtualizedList::make(Text::make('One'), $banner ? Text::make('New') : null, Text::make('Two')),
    Input::make('draft'),
)->toElement();
$apiEncoder = new TreeEncoder();
$apiList = [];
$apiInput = [];
$apiRows = [];
foreach ([[false, false], [true, true], [false, true], [true, false], [false, false]] as [$overlay, $banner]) {
    $nodes = $encodedNodes($apiEncoder, $apiTree($overlay, $banner));
    $apiList[] = $idsOf($nodes, NodeKind::VirtualList)[0];
    $apiInput[] = $idsOf($nodes, NodeKind::Input)[0];
    $texts = $idsOf($nodes, NodeKind::Text);
    $apiRows[] = end($texts);
}
$assert(
    count(array_unique($apiList)) === 1
        && count(array_unique($apiInput)) === 1
        && count(array_unique($apiRows)) === 1,
    'Element API arrays with null/false children must keep holes so following siblings keep their identity.',
);
$holeTree = Column::make(null, Text::make('a'), false, Text::make('b'))->toElement();
$assert(
    count($holeTree->children()) === 2
        && $holeTree->children()[0]->identitySlot() === '1'
        && $holeTree->children()[1]->identitySlot() === '3',
    'Holes must be dropped from the output while children keep their argument slot.',
);

// Component boundaries: children passed through a component and conditional
// content inside a component follow the same rule.
TemplateRegistry::reset();
TemplateRegistry::component(
    'IdentityCard',
    static fn (array $props, array $children): \Pam\Native\Renderable => Column::make(...$children),
);
TemplateRegistry::component(
    'IdentityComposer',
    static fn (array $props): \Pam\Native\Renderable => Column::make(
        ($props['banner'] ?? false) ? Text::make('Typing') : null,
        Input::make('draft'),
    ),
);
$componentTemplate = TemplateCompiler::compile(
    '<Column><Text p-if="$banner">Offline</Text>'
    .'<IdentityCard><Text p-if="$banner">Pinned</Text><Input value="a" /></IdentityCard>'
    .'<IdentityComposer :banner="$banner" /></Column>',
);
$componentIds = [];
foreach ([false, true, false] as $banner) {
    $nodes = $encodedNodes(new TreeEncoder(), TemplateRenderer::render($componentTemplate, null, ['banner' => $banner]));
    $componentIds[] = implode(',', $idsOf($nodes, NodeKind::Input));
}
$assert(
    count(array_unique($componentIds)) === 1 && substr_count($componentIds[0], ',') === 1,
    'Component boundaries must keep unkeyed identities stable across conditional siblings.',
);

// Slotted content composed with positional children never collides.
$mixed = Column::make(
    Text::make('Header'),
    ...TemplateRenderer::render(
        TemplateCompiler::compile('<Column><Text>A</Text><Text>B</Text></Column>'),
        null,
        [],
    )->children(),
)->toElement();
$mixedNodes = $encodedNodes(new TreeEncoder(), $mixed);
$assert(
    count($idsOf($mixedNodes, NodeKind::Text)) === 3,
    'Slotted children mixed with positional children must not collide.',
);

// p-for children follow their keys; unkeyed loops warn in development.
$loopTemplate = TemplateCompiler::compile(
    '<VirtualizedList><Text p-for="$row in $rows" p-key="$row">{{ $row }}</Text></VirtualizedList>',
);
$loopIds = static function (array $rows) use ($encodedNodes, $loopTemplate): array {
    $nodes = $encodedNodes(new TreeEncoder(), TemplateRenderer::render($loopTemplate, null, ['rows' => $rows]));
    $byText = [];
    foreach ($nodes as $node) {
        if ($node->kind === NodeKind::Text->value) {
            $byText[] = $node->id;
        }
    }

    return $byText;
};
$forward = $loopIds(['a', 'b', 'c']);
$reversed = $loopIds(['c', 'b', 'a']);
$assert(
    $forward === array_reverse($reversed),
    'p-for children keyed with p-key must keep their identity when reordered.',
);
DevWarnings::reset();
TemplateRenderer::render(
    TemplateCompiler::compile('<Column><Text p-for="$row in $rows">{{ $row }}</Text></Column>'),
    null,
    ['rows' => ['a', 'b']],
);
$assert(
    count(DevWarnings::messages()) === 1 && str_contains(DevWarnings::messages()[0], 'p-key'),
    'Unkeyed p-for children must warn in development.',
);
DevWarnings::reset();

// The identity pass stays linear: a 2,000-row template with conditional
// siblings encodes within the first-frame budget.
$wideTemplate = TemplateCompiler::compile(
    '<Column><Text p-if="$banner">Offline</Text>'
    .'<VirtualizedList><Text p-for="$row in $rows" p-key="$row">Row</Text></VirtualizedList>'
    .'<Input value="x" /></Column>',
);
$wideRows = range(1, 2_000);
$wideEncoder = new TreeEncoder();
$started = hrtime(true);
foreach ([false, true, false, true] as $banner) {
    $wideEncoder->encode(TemplateRenderer::render($wideTemplate, null, ['banner' => $banner, 'rows' => $wideRows]));
}
$wideMs = (hrtime(true) - $started) / 1_000_000 / 4;
$assert(
    $wideMs < (float) (getenv('PAM_PERF_CONDITIONAL_FRAME_MS') ?: 400),
    "Conditional identity rendering exceeded its budget ({$wideMs} ms).",
);
TemplateRegistry::reset();
