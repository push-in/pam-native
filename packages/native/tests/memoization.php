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

    public function __construct()
    {
        $this->child = new MemoTestChild();
        $this->volatile = new MemoTestVolatile();
    }

    public function render(): Element
    {
        self::$renders++;

        return Screen::make(Column::make(
            Text::make("count-{$this->count}"),
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

\Pam\Native\Internal\DependencyTracker::memoization(false);
$memoResult(static function (): void {});
$assert(MemoTestParent::$renders === 6, 'Disabling memoization must restore full re-renders.');
\Pam\Native\Internal\DependencyTracker::memoization(true);
$assert(MemoTestChild::$unmounts === 0, 'Memoized subtrees must never be unmounted while displayed.');
Runtime::shutdown();
