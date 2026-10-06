<?php

declare(strict_types=1);

use Pam\Native\Component;
use Pam\Native\EventKind;
use Pam\Native\Navigation\InteractsWithNavigationLifecycle;
use Pam\Native\Navigation\NavigationLifecycleAware;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\RouteContext;
use Pam\Native\Navigation\Router;
use Pam\Native\Renderable;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

if (!class_exists(Navigator::class)) {
    spl_autoload_register(static function (string $class): void {
        if (!str_starts_with($class, 'Pam\\Native\\')) return;
        $path = __DIR__.'/../src/'.str_replace('\\', '/', substr($class, strlen('Pam\\Native\\'))).'.php';
        if (is_file($path)) require $path;
    });
}
$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

final class NavigationLifecycleFixture extends Component implements NavigationLifecycleAware
{
    use InteractsWithNavigationLifecycle;

    public int $focused = 0;
    public int $blurred = 0;
    /** @var list<RouteContext> */
    public array $removed = [];

    public function render(): Renderable { return Screen::make(Text::make('Lifecycle')); }
    public function navigationFocused(RouteContext $route): void { ++$this->focused; }
    public function navigationBlurred(RouteContext $route): void { ++$this->blurred; }
    public function navigationRemoved(RouteContext $route): void { $this->removed[] = $route; }
}

$fixture = static function (bool $keepAlive = false): array {
    $screens = [];
    $router = Router::stack('root');
    foreach (['root', 'middle', 'top'] as $name) {
        $screen = $screens[$name] = new NavigationLifecycleFixture();
        $router = $router->route($name, static fn () => $screen);
    }
    if ($keepAlive) $router = $router->keepAlive('middle');
    return [$router->build(), $screens];
};
$settle = static function (Navigator $navigator): void {
    $host = $navigator->render()->toElement();
    ($host->events()[EventKind::AnimationComplete->value])('');
};

[$navigator, $screens] = $fixture();
$settle($navigator);
$navigator->push('middle', ['id' => 7]);
$settle($navigator);
$navigator->push('top');
$settle($navigator);
$assert($screens['root']->blurred === 1 && $screens['root']->removed === [] && $screens['middle']->removed === [], 'Push only blurs retained routes, including a route deeper than the mounted native screens.');
$navigator->pop();
$assert($screens['top']->removed === [], 'The popped screen stays alive until its exit animation settles.');
$settle($navigator);
$settle($navigator);
$assert(count($screens['top']->removed) === 1 && $screens['middle']->focused === 2, 'Pop removes once and returning focuses the retained instance.');
$navigator->reset('root');
$assert(count($screens['root']->removed) === 1 && count($screens['middle']->removed) === 1, 'Reset removes every instantiated discarded route, including covered history.');
$assert($screens['middle']->removed[0]->name === 'middle' && $screens['middle']->removed[0]->integer('id') === 7, 'Removal receives the discarded route identity and params.');
$settle($navigator);
$assert(count($screens['root']->removed) === 1, 'Settling reset does not remove the new root instance again.');

[$navigator, $screens] = $fixture();
$settle($navigator);
$navigator->push('middle');
$settle($navigator);
$navigator->push('top');
$settle($navigator);
$navigator->popToTop();
$assert(count($screens['middle']->removed) === 1 && $screens['top']->removed === [], 'popToTop removes intermediate history while the outgoing top waits for its animation.');
$settle($navigator);
$assert(count($screens['top']->removed) === 1 && $screens['root']->removed === [], 'popToTop retains its destination and removes its outgoing route once.');

[$navigator, $screens] = $fixture();
$settle($navigator);
$navigator->push('middle');
$settle($navigator);
$navigator->pop();
$navigator->push('top');
$assert(count($screens['middle']->removed) === 1, 'A new push interrupting a pop releases the previously outgoing route.');
$settle($navigator);
$assert(count($screens['middle']->removed) === 1 && $screens['root']->removed === [], 'An interrupted animation never releases a retained route or duplicates teardown.');

[$navigator, $screens] = $fixture(true);
$settle($navigator);
$navigator->push('middle');
$settle($navigator);
$navigator->pop();
$settle($navigator);
$assert($screens['middle']->removed === [], 'A parked keep-alive screen is not removed.');
$navigator->push('middle');
$settle($navigator);
$assert($screens['middle']->focused === 2 && $screens['middle']->removed === [], 'Unparking refocuses the same screen without teardown.');
$navigator->pop();
$settle($navigator);
$navigator->reset('top');
$assert(count($screens['middle']->removed) === 1 && count($screens['root']->removed) === 1, 'Reset releases parked screens together with discarded stack history.');

[$navigator, $screens] = $fixture();
$settle($navigator);
$navigator->push('middle');
$settle($navigator);
$saved = $navigator->saveState();
$navigator->restoreState($saved);
$assert(count($screens['root']->removed) === 1 && count($screens['middle']->removed) === 1, 'Restoring navigation state releases the replaced route instances.');
$settle($navigator);
$assert($screens['middle']->focused === 2, 'A restored current route receives focus with its new identity.');

[$navigator, $screens] = $fixture(true);
$settle($navigator);
for ($id = 1; $id <= 4; ++$id) {
    $navigator->push('middle', ['id' => $id]);
    $settle($navigator);
    $navigator->pop();
    $settle($navigator);
}
$assert(count($screens['middle']->removed) === 1 && $screens['middle']->removed[0]->integer('id') === 1, 'Exceeding the parked route budget releases only the evicted oldest instance.');

echo "Navigation lifecycle tests passed.\n";
