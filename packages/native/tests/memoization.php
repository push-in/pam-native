<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Attributes\AlwaysRender;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Internal\Runtime;
use Pam\Native\ModuleResultStatus;
use Pam\Native\UI\Column;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

Runtime::shutdown();

final class MemoTestChild extends Component
{
    public static int $renders = 0;
    public static int $unmounts = 0;
    public string $label = 'label-alpha';

    public function render(): Element
    {
        self::$renders++;

        return Text::make($this->label)->toElement();
    }

    public function unmount(): void
    {
        self::$unmounts++;
    }
}

#[AlwaysRender]
final class MemoTestVolatile extends Component
{
    public static int $renders = 0;

    public function render(): Element
    {
        self::$renders++;

        return Text::make('volatile')->toElement();
    }
}

final class MemoTestParent extends Component
{
    public static int $renders = 0;
    public int $count = 0;
    public MemoTestChild $child;
    public MemoTestVolatile $volatile;
    private stdClass $model;

    public function __construct()
    {
        $this->child = new MemoTestChild();
        $this->volatile = new MemoTestVolatile();
        $this->model = new stdClass();
        $this->model->title = 'model-one';
    }

    public function model(): stdClass
    {
        return $this->model;
    }

    public function render(): Element
    {
        self::$renders++;

        return Screen::make(Column::make(
            Text::make("count-{$this->count}"),
            Text::make($this->model->title),
            $this->child,
            $this->volatile,
        ))->toElement();
    }
}

$memoParent = new MemoTestParent();
App::run($memoParent);
$memoResult = static function (Closure $callback): void {
    $request = Runtime::call('test.memo', 'noop', '', static function () use ($callback): void {
        $callback();
    });
    Runtime::dispatchModuleResult($request, ModuleResultStatus::Success->value, '');
};
$assert(
    MemoTestParent::$renders === 1 && MemoTestChild::$renders === 1,
    'Memoization test app must render once at boot.',
);
$frameBefore = Runtime::lastFrame();
$memoResult(static function (): void {});
$assert(
    MemoTestParent::$renders === 1 && MemoTestChild::$renders === 1
        && Runtime::lastFrame() === $frameBefore,
    'A module result that changes no component state must not re-render or commit.',
);
$assert(MemoTestChild::$unmounts === 0, 'Components under a memoized parent must stay mounted.');

$memoResult(static function () use ($memoParent): void {
    $memoParent->child->label = 'label-beta';
});
$assert(
    MemoTestChild::$renders === 2 && MemoTestParent::$renders === 2
        && str_contains((string) Runtime::lastFrame(), 'label-beta'),
    'A direct write to a nested component must re-render it and its ancestors.',
);

$volatileBefore = MemoTestVolatile::$renders;
$memoResult(static function () use ($memoParent): void {
    $memoParent->count = 7;
});
$assert(
    MemoTestParent::$renders === 3 && MemoTestChild::$renders === 2
        && MemoTestVolatile::$renders === $volatileBefore + 1
        && str_contains((string) Runtime::lastFrame(), 'count-7'),
    'Unchanged children must be reused while #[AlwaysRender] components re-render.',
);

Runtime::requestRender();
$assert(
    MemoTestParent::$renders === 4 && MemoTestChild::$renders === 3,
    'requestRender() must invalidate every memoized component.',
);

Runtime::deferRendering(true);
$memoResult(static function () use ($memoParent): void {
    $memoParent->count = 8;
});
$memoResult(static function () use ($memoParent): void {
    $memoParent->count = 9;
});
$assert(
    MemoTestParent::$renders === 4 && Runtime::hasPendingRender(),
    'Deferred rendering must coalesce dispatched results until the host flushes.',
);
$assert(Runtime::flush() && MemoTestParent::$renders === 5
    && str_contains((string) Runtime::lastFrame(), 'count-9')
    && !Runtime::flush(),
    'flush() must render a coalesced burst exactly once.');
Runtime::deferRendering(false);

$beforeModel = MemoTestParent::$renders;
$memoResult(static function () use ($memoParent): void {
    $memoParent->model()->title = 'model-two';
});
$assert(
    MemoTestParent::$renders === $beforeModel + 1
        && str_contains((string) Runtime::lastFrame(), 'model-two'),
    'In-place writes to plain objects held by a component must re-render it.',
);

\Pam\Native\Internal\DependencyTracker::memoization(false);
$memoResult(static function (): void {});
$assert(MemoTestParent::$renders === $beforeModel + 2, 'Disabling memoization must restore full re-renders.');
\Pam\Native\Internal\DependencyTracker::memoization(true);
$assert(MemoTestChild::$unmounts === 0, 'Memoized subtrees must never be unmounted while displayed.');
Runtime::shutdown();

final class MemoFreezeScreen extends Component
{
    public static array $renders = [];
    public static int $unmounts = 0;
    public string $label;

    public function __construct(public string $name)
    {
        $this->label = $name.'-initial';
    }

    public function render(): Element
    {
        self::$renders[$this->name] = (self::$renders[$this->name] ?? 0) + 1;

        return Screen::make(Text::make($this->label))->toElement();
    }

    public function unmount(): void
    {
        self::$unmounts++;
    }
}

$freezeScreens = ['first' => new MemoFreezeScreen('first'), 'second' => new MemoFreezeScreen('second')];
$freezeNavigator = new \Pam\Native\Navigation\Navigator(
    initialRoute: 'first',
    routes: [
        'first' => static fn () => $freezeScreens['first'],
        'second' => static fn () => $freezeScreens['second'],
    ],
    transition: \Pam\Native\Navigation\NavigationTransition::Fade,
    handleSystemBack: false,
);
App::run($freezeNavigator);
$memoResult(static function () use ($freezeNavigator): void {
    $freezeNavigator->push('second');
});
$firstRenders = MemoFreezeScreen::$renders['first'];
$memoResult(static function () use ($freezeScreens): void {
    $freezeScreens['first']->label = 'first-changed';
});
$assert(
    MemoFreezeScreen::$renders['first'] === $firstRenders
        && MemoFreezeScreen::$unmounts === 0
        && !str_contains((string) Runtime::lastFrame(), 'first-changed'),
    'Screens below the top of the stack must stay frozen and mounted while inactive.',
);
$memoResult(static function () use ($freezeNavigator): void {
    $freezeNavigator->pop();
});
$assert(
    MemoFreezeScreen::$renders['first'] > $firstRenders
        && str_contains((string) Runtime::lastFrame(), 'first-changed'),
    'A frozen screen must render its latest state once it becomes the top again.',
);
Runtime::shutdown();

// A memoized keyed subtree that moves to another sibling index must be
// re-placed, not replayed with its cached index (DuplicateSiblingIndex).
$movingScreens = [
    'a' => new MemoFreezeScreen('a'),
    'b' => new MemoFreezeScreen('b'),
    'c' => new MemoFreezeScreen('c'),
];
$movingNavigator = new \Pam\Native\Navigation\Navigator(
    initialRoute: 'a',
    routes: [
        'a' => static fn () => $movingScreens['a'],
        'b' => static fn () => $movingScreens['b'],
        'c' => static fn () => $movingScreens['c'],
    ],
    transition: \Pam\Native\Navigation\NavigationTransition::Fade,
    handleSystemBack: false,
);
App::run($movingNavigator);
$movingEncoder = new \Pam\Native\Internal\TreeEncoder();
$movingIndexes = static function () use ($movingNavigator, $movingEncoder, $assert): void {
    \Pam\Native\Internal\PamPhpRegistry::beginRender();
    \Pam\Native\Internal\ComponentLifecycle::beginRender();
    $element = $movingNavigator->toElement();
    \Pam\Native\Internal\ComponentLifecycle::finishRender();
    \Pam\Native\Internal\PamPhpRegistry::finishRender();
    $movingEncoder->encode($element);
    $nodes = (new ReflectionProperty($movingEncoder, 'nodes'))->getValue($movingEncoder);
    $seen = [];
    foreach ($nodes as $node) {
        $slot = $node->parent.':'.$node->index;
        $assert(!isset($seen[$slot]), 'Encoded siblings must never share an index.');
        $seen[$slot] = true;
    }
};
$movingIndexes();
$movingNavigator->push('b');
$movingIndexes();
$movingNavigator->push('c');
$movingIndexes();
$movingNavigator->pop();
$movingIndexes();
Runtime::shutdown();
