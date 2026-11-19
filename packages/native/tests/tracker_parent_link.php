<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Component;
use Pam\Native\Element;
use Pam\Native\Internal\Runtime;
use Pam\Native\ModuleResultStatus;
use Pam\Native\UI\Column;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

Runtime::shutdown();

$trackerResult = static function (Closure $callback): void {
    $request = Runtime::call('test.tracker', 'noop', '', static function () use ($callback): void {
        $callback();
    });
    Runtime::dispatchModuleResult($request, ModuleResultStatus::Success->value, '');
};

// DependencyTracker: a child that renders outside a render pass (no
// component on the tracking stack) keeps the parent it was rendered under,
// so its later changes still reach the memoized parent.

final class TrackerTestOrphanChild extends Component
{
    public static int $renders = 0;
    public string $label = 'orphan-one';

    public function render(): Element
    {
        self::$renders++;

        return Text::make($this->label)->toElement();
    }
}

final class TrackerTestOrphanParent extends Component
{
    public static int $renders = 0;
    public TrackerTestOrphanChild $child;

    public function __construct()
    {
        $this->child = new TrackerTestOrphanChild();
    }

    public function render(): Element
    {
        self::$renders++;

        return Screen::make(Column::make($this->child))->toElement();
    }
}

$orphanParent = new TrackerTestOrphanParent();
App::run($orphanParent);
$orphanParent->child->label = 'orphan-two';
// Rendered outside any render (a test helper, a measuring callback).
$orphanParent->child->toElement();
$trackerResult(static function () use ($orphanParent): void {
    $orphanParent->child->label = 'orphan-three';
});
$assert(
    str_contains((string) Runtime::lastFrame(), 'orphan-three'),
    'A child rendered outside a render pass must keep its tracked parent so later changes still re-render through it.',
);
Runtime::shutdown();
