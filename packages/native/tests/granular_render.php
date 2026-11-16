<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Component;
use Pam\Native\Diagnostics\Profiler;
use Pam\Native\EventKind;
use Pam\Native\Internal\DependencyTracker;
use Pam\Native\Internal\GranularRender;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\TreeEncoder;
use Pam\Native\ModuleResultStatus;
use Pam\Native\PropKey;

// Granular re-render: a component dirty only through a descendant keeps its
// last element and renders again just the path to that descendant (its
// template does not run); the result equals a full render.
$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};
if (!function_exists('pam_native_commit')) {
    function pam_native_commit(string $frame): bool
    {
        return true;
    }
}
Runtime::shutdown();
$granularDirectory = sys_get_temp_dir().'/pam-native-granular-'.getmypid();
if (!is_dir($granularDirectory) && !mkdir($granularDirectory, 0o755, true) && !is_dir($granularDirectory)) {
    throw new RuntimeException('Cannot create the granular render fixture directory.');
}
file_put_contents($granularDirectory.'/GranularChild.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Granular;

use Pam\Native\Component;

final class GranularChild extends Component
{
    /** @var array<string, int> */
    public static array $renders = [];
    /** @var array<string, int> */
    public static array $mounts = [];
    /** @var array<string, int> */
    public static array $unmounts = [];
    /** @var array<string, self> */
    public static array $instances = [];

    public int $count = 0;
    public bool $explode = false;

    public function __construct(public string $ident = '')
    {
    }

    public function mount(): void
    {
        self::$mounts[$this->ident] = (self::$mounts[$this->ident] ?? 0) + 1;
        self::$instances[$this->ident] = $this;
    }

    public function unmount(): void
    {
        self::$unmounts[$this->ident] = (self::$unmounts[$this->ident] ?? 0) + 1;
    }

    public function rendering(): void
    {
        self::$renders[$this->ident] = (self::$renders[$this->ident] ?? 0) + 1;
    }

    public function label(): string
    {
        if ($this->explode) {
            throw new \RuntimeException('granular child failed');
        }

        return $this->ident.'='.$this->count;
    }

    public function bump(): void
    {
        $this->count++;
    }
}
?>

<template>
    <Row>
        <Text>{{ label() }}</Text>
        <GranularLeaf p-if="$count % 2 === 1" :owner="$ident" />
        <Pressable on:press="bump"><Text>bump</Text></Pressable>
    </Row>
</template>
PAM);
file_put_contents($granularDirectory.'/GranularLeaf.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Granular;

use Pam\Native\Component;

final class GranularLeaf extends Component
{
    public static int $mounts = 0;
    public static int $unmounts = 0;

    public function __construct(public string $owner = '')
    {
    }

    public function mount(): void
    {
        self::$mounts++;
    }

    public function unmount(): void
    {
        self::$unmounts++;
    }
}
?>

<template>
    <Text>leaf-{{ $owner }}</Text>
</template>
PAM);
file_put_contents($granularDirectory.'/GranularCard.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Granular;

use Pam\Native\Component;

final class GranularCard extends Component
{
}
?>

<template>
    <View padding="2">
        <Slot />
    </View>
</template>
PAM);
file_put_contents($granularDirectory.'/GranularParent.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Granular;

use Pam\Native\Component;
use Pam\Native\ErrorContext;
use Pam\Native\Renderable;
use Pam\Native\UI\Text;

final class GranularParent extends Component
{
    public static int $renders = 0;
    public static int $failures = 0;

    public bool $showB = false;
    /** @var list<int> */
    public array $items = [1, 2];

    public function rendering(): void
    {
        self::$renders++;
    }

    public function failed(\Throwable $error, ErrorContext $context): ?Renderable
    {
        self::$failures++;

        return Text::make('recovered: '.$error->getMessage());
    }
}
?>

<template>
    <Column>
        <Text>parent</Text>
        <GranularChild ident="a" key="child-a" class="decorated" />
        <GranularChild p-if="$showB" ident="b" />
        <Row p-for="$n in $items" p-key="$n">
            <GranularChild :ident="'item-'.$n" />
        </Row>
        <GranularCard><GranularChild ident="slotted" /></GranularCard>
    </Column>
</template>

<style scoped>
.decorated { padding: 3px; background-color: #123456; }
</style>
PAM);
file_put_contents($granularDirectory.'/GranularShell.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Granular;

use Pam\Native\Component;

final class GranularShell extends Component
{
    public static int $renders = 0;

    public function rendering(): void
    {
        self::$renders++;
    }
}
?>

<template>
    <Screen>
        <Text>shell</Text>
        <GranularParent class="framed" />
    </Screen>
</template>

<style scoped>
.framed { margin: 4px; }
</style>
PAM);
file_put_contents($granularDirectory.'/GranularList.pam.php', <<<'PAM'
<?php

declare(strict_types=1);

namespace Pam\Native\Tests\Granular;

use Pam\Native\Component;

final class GranularList extends Component
{
    public static int $renders = 0;

    /** @var list<int> */
    public array $rows;

    public function __construct()
    {
        $this->rows = range(1, 120);
    }

    public function rendering(): void
    {
        self::$renders++;
    }
}
?>

<template>
    <Screen>
        <VirtualizedList>
            <GranularChild p-for="$n in $rows" p-key="$n" :ident="'row-'.$n" />
        </VirtualizedList>
    </Screen>
</template>
PAM);
App::components($granularDirectory, $granularDirectory.'/.cache');

$granularChild = 'Pam\\Native\\Tests\\Granular\\GranularChild';
$granularLeaf = 'Pam\\Native\\Tests\\Granular\\GranularLeaf';
$granularParent = 'Pam\\Native\\Tests\\Granular\\GranularParent';
$granularShellClass = 'Pam\\Native\\Tests\\Granular\\GranularShell';
$granularResult = static function (Closure $callback): void {
    $request = Runtime::call('test.granular', 'noop', '', static function () use ($callback): void {
        $callback();
    });
    Runtime::dispatchModuleResult($request, ModuleResultStatus::Success->value, '');
};
// Full frame and event wiring of a component's current tree, from a fresh encoder.
$granularEncoding = static function (Component $root): array {
    $encoded = (new TreeEncoder())->encode($root->__pamRendered());

    return [$encoded['frame'], array_keys($encoded['callbacks'])];
};
// The tree a full render (every template run again) produces now.
$granularReference = static function (Component $root) use ($granularEncoding): array {
    Runtime::requestRender();

    return $granularEncoding($root);
};
$frameHas = static fn (string $text): bool => str_contains((string) Runtime::lastFrame(), $text);
$spanCount = static function (string $name, string $component): int {
    $count = 0;
    foreach (Profiler::spans() as $span) {
        if ($span->name === $name && ($span->metadata['component'] ?? null) === $component) {
            $count++;
        }
    }

    return $count;
};

$granularShell = App::make($granularShellClass);
App::run($granularShell);
$child = static fn (string $ident) => $granularChild::$instances[$ident];
$assert(
    $granularShellClass::$renders === 1 && $granularParent::$renders === 1 && ($granularChild::$renders['a'] ?? 0) === 1,
    'The granular fixture must render once at boot.',
);
$assert($frameHas('a=0') && $frameHas('item-2=0') && $frameHas('slotted=0'), 'The granular fixture must show its children.');

// (a) A child's own state changes: its ancestors keep their render.
Profiler::reset();
$granularResult(static function () use ($child): void {
    $child('a')->count = 2;
});
$assert(
    $granularChild::$renders['a'] === 2
        && $granularParent::$renders === 1
        && $granularShellClass::$renders === 1
        && ($granularChild::$renders['item-1'] ?? 0) === 1,
    'A dirty child must render alone: its parent and grandparent must not run their templates.',
);
$assert($frameHas('a=2'), 'The frame must carry the child\'s new output.');
$assert(
    $spanCount('component.render', $granularParent) === 0
        && $spanCount('component.patch', $granularParent) === 1
        && $spanCount('component.patch', $granularShellClass) === 1,
    'Granular updates must show in the profiler as component.patch, not component.render.',
);
// (b) The call site's class and attributes stay on the child's new element.
$placedA = $child('a')->__pamPlacement()?->placed;
$assert(
    $placedA !== null
        && isset($placedA->properties()[PropKey::BackgroundColor->value])
        && !isset($child('a')->__pamRendered()->properties()[PropKey::BackgroundColor->value])
        && $placedA->elementKey() === 'child-a',
    'The child keeps the class and key its call site gave it.',
);
$patchedEncoding = $granularEncoding($granularShell);
$parentRenders = $granularParent::$renders;
$assert(
    $patchedEncoding === $granularReference($granularShell) && $granularParent::$renders === $parentRenders + 1,
    'A granular update must produce exactly the tree a full render produces (decoration, slots, events).',
);

// Through an event handler of the child (the handler marks its component).
$bumpNode = null;
foreach ((new ReflectionProperty(Runtime::class, 'eventCallbacks'))->getValue() as $key => $callback) {
    $statics = (new ReflectionFunction($callback))->getStaticVariables();
    if (($statics['scope'] ?? null) === $child('item-1') && str_ends_with((string) $key, ':'.EventKind::Press->value)) {
        $bumpNode = array_map('intval', explode(':', (string) $key));
    }
}
$assert($bumpNode !== null, 'The fixture child must have a press handler.');
$parentRenders = $granularParent::$renders;
Runtime::dispatchEvent($bumpNode[0], $bumpNode[1], '');
$assert(
    $frameHas('item-1=1') && $granularParent::$renders === $parentRenders,
    'A child event handler must update the child without its parent rendering.',
);

// (f) Lifecycle: a patched child mounts and unmounts its own children; the
// rest stays mounted.
$treeHas = static fn (string $text): bool => str_contains((string) $granularEncoding($granularShell)[0], $text);
$leafMounts = $granularLeaf::$mounts;
$leafUnmounts = $granularLeaf::$unmounts;
$granularResult(static function () use ($child): void {
    $child('a')->count = 3;
});
$assert(
    $granularLeaf::$mounts === $leafMounts + 1 && $granularLeaf::$unmounts === $leafUnmounts && $frameHas('leaf-a'),
    'A child rendering again without its parent must mount what it adds.',
);
$granularResult(static function () use ($child): void {
    $child('item-2')->count = 5;
});
$assert(
    $granularLeaf::$mounts === $leafMounts + 2 && $granularLeaf::$unmounts === $leafUnmounts
        && $granularParent::$renders === $parentRenders
        && $treeHas('leaf-a') && $treeHas('leaf-item-2'),
    'Two children each mounting a leaf: sibling updates keep the other leaf mounted.',
);
$granularResult(static function () use ($child): void {
    $child('item-2')->count = 6;
});
$assert(
    $granularLeaf::$unmounts === $leafUnmounts + 1 && $granularLeaf::$mounts === $leafMounts + 2
        && !$treeHas('leaf-item-2') && $treeHas('leaf-a'),
    'A child rendering again without its parent must unmount what it drops.',
);
$assert(
    array_sum($granularChild::$unmounts) === 0 && $granularChild::$mounts['a'] === 1,
    'Granular updates must never unmount or remount the components they keep.',
);
$granularResult(static function () use ($child): void {
    $child('a')->count = 4;
});
$assert(
    $granularLeaf::$unmounts === $leafUnmounts + 2 && !$treeHas('leaf-a'),
    'The leaf of a patched child unmounts when it drops it.',
);

// (d) Two dirty children in one pass.
$granularResult(static function () use ($child): void {
    $child('a')->count = 10;
    $child('item-1')->count = 20;
});
$assert(
    $frameHas('a=10') && $frameHas('item-1=20') && $granularParent::$renders === $parentRenders,
    'Two dirty children must both update without their parent rendering.',
);
$patchedEncoding = $granularEncoding($granularShell);
$assert($patchedEncoding === $granularReference($granularShell), 'Two granular updates must equal a full render.');

// (e) The parent adds and removes children in the same pass a child changes.
$parentRenders = $granularParent::$renders;
$shellRenders = $granularShellClass::$renders;
$granularResult(static function () use ($child, $granularShell): void {
    $child('a')->count = 11;
    $parent = null;
    foreach (\Pam\Native\Internal\ComponentLifecycle::children($granularShell) as $candidate) {
        $parent = $candidate;
    }
    $parent->showB = true;
    $parent->items = [2];
});
$assert(
    $granularParent::$renders === $parentRenders + 1
        && $granularShellClass::$renders === $shellRenders
        && $frameHas('a=11') && $frameHas('b=0') && !$frameHas('item-1=')
        && ($granularChild::$unmounts['item-1'] ?? 0) === 1
        && ($granularChild::$mounts['b'] ?? 0) === 1,
    'A parent changing its own children renders (its parent keeps its render): additions mount, removals unmount.',
);
$assert($granularEncoding($granularShell) === $granularReference($granularShell), 'Adding/removing under a patched ancestor must equal a full render.');
$parentRenders = $granularParent::$renders;
$bRenders = $granularChild::$renders['b'];
$granularResult(static function () use ($child): void {
    $child('b')->count = 1;
});
$assert(
    $frameHas('b=1') && $granularParent::$renders === $parentRenders && $granularChild::$renders['b'] === $bRenders + 1,
    'A child added by its parent updates granularly afterwards.',
);

// (c) Fallbacks: slot content (placed by another component) renders its owner.
$parentRenders = $granularParent::$renders;
$granularResult(static function () use ($child): void {
    $child('slotted')->count = 7;
});
$assert(
    $frameHas('slotted=7') && $granularParent::$renders === $parentRenders + 1,
    'A dirty child given as slot content must fall back to its owner\'s full render.',
);
$assert($granularEncoding($granularShell) === $granularReference($granularShell), 'The slot fallback must equal a full render.');

// A child that fails renders inside its parent's error boundary, as before.
$parentFailures = $granularParent::$failures;
$parentRenders = $granularParent::$renders;
$aRenders = $granularChild::$renders['a'];
$granularResult(static function () use ($child): void {
    $child('a')->explode = true;
});
$assert(
    $granularParent::$failures === $parentFailures + 1 && $frameHas('recovered: granular child failed')
        && $granularChild::$renders['a'] === $aRenders + 1 && $granularParent::$renders === $parentRenders,
    'A child failing during a granular update must reach its parent\'s failed() boundary once, as from its render.',
);
$assert(
    ($granularChild::$unmounts['a'] ?? 0) === 0,
    'The failing child stays mounted behind its parent\'s recovery (its next change renders it again).',
);
$parentRenders = $granularParent::$renders;
$granularResult(static function () use ($child): void {
    $child('a')->explode = false;
    $child('a')->count = 14;
});
$assert(
    $treeHas('a=14') && !$treeHas('recovered') && $granularParent::$renders === $parentRenders + 1,
    'After the failure the child is no longer in its parent\'s tree: the parent renders completely.',
);
$assert($granularEncoding($granularShell) === $granularReference($granularShell), 'Recovering from a failure must equal a full render.');

// Disabled granular updates (and disabled memoization) render the ancestors.
GranularRender::enabled(false);
$parentRenders = $granularParent::$renders;
$granularResult(static function () use ($child): void {
    $child('a')->count = 12;
});
$assert($frameHas('a=12') && $granularParent::$renders === $parentRenders + 1, 'Disabled granular updates must render the parent.');
GranularRender::enabled(true);
DependencyTracker::memoization(false);
$parentRenders = $granularParent::$renders;
$granularResult(static function () use ($child): void {
    $child('a')->count = 13;
});
$assert($frameHas('a=13') && $granularParent::$renders === $parentRenders + 1, 'Without memoization every template renders.');
DependencyTracker::memoization(true);
Runtime::shutdown();

// (c) A row of a lazy (virtualized) list falls back to its owner's render.
$granularListClass = 'Pam\\Native\\Tests\\Granular\\GranularList';
$granularChild::$instances = [];
App::run(App::make($granularListClass));
$listRenders = $granularListClass::$renders;
$assert($frameHas('row-1=0') && isset($granularChild::$instances['row-1']), 'The lazy list must materialize its first rows.');
$granularResult(static function () use ($child): void {
    $child('row-1')->count = 9;
});
$assert(
    $frameHas('row-1=9') && $granularListClass::$renders === $listRenders + 1,
    'A dirty lazy row must fall back to its owner\'s full render and show its change.',
);
$granularResult(static function () use ($child): void {
    $child('row-2')->count = 4;
});
$assert($frameHas('row-2=4'), 'Lazy rows keep updating after the fallback.');
Runtime::shutdown();

echo "GRANULAR_RENDER_OK patch decoration events lifecycle multi add-remove slot-fallback error-boundary lazy-fallback\n";
