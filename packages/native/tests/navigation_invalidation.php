<?php

declare(strict_types=1);

use Pam\Native\Component;
use Pam\Native\EventKind;
use Pam\Native\Internal\DependencyTracker;
use Pam\Native\Internal\Runtime;
use Pam\Native\Navigation\Navigator;
use Pam\Native\Navigation\Router;
use Pam\Native\Renderable;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

$assert ??= static function (bool $condition, string $message): void {
    if (!$condition) throw new RuntimeException($message);
};

// 1.49.12: the host's transition and gesture callbacks and a Back that pops
// a route re-render the navigator they changed, not the whole tree (every
// kept-alive screen rendered again after each push, pop and Back).
final class NavigationInvalidationScreen extends Component
{
    public function render(): Renderable { return Screen::make(Text::make('Screen')); }
}

$build = static function (): Navigator {
    $router = Router::stack('root');
    foreach (['root', 'top'] as $name) {
        $router = $router->route($name, static fn () => new NavigationInvalidationScreen());
    }

    return $router->build();
};
$isPure = new ReflectionMethod(Runtime::class, 'isPureCallback');
$navigator = $build();
$navigator->push('top');
$events = $navigator->render()->toElement()->events();
foreach ([
    EventKind::AnimationComplete,
    EventKind::GestureBegin,
    EventKind::GestureEnd,
    EventKind::GestureCancel,
    EventKind::NavigationGesturePop,
] as $kind) {
    $callback = $events[$kind->value] ?? null;
    $assert($callback !== null, "The navigation host listens to {$kind->name}.");
    $assert($isPure->invoke(null, $callback) === true, "The navigator's {$kind->name} callback does not invalidate the whole tree.");
}
$epoch = DependencyTracker::epoch();
($events[EventKind::AnimationComplete->value])('');
$assert(DependencyTracker::epoch() === $epoch, 'A settled transition keeps the dependency epoch.');
$assert(DependencyTracker::isDirty($navigator), 'A settled transition re-renders its navigator.');

$navigator = $build();
$navigator->push('top');
($navigator->render()->toElement()->events()[EventKind::AnimationComplete->value])('');
$epoch = DependencyTracker::epoch();
$assert($navigator->dispatchSystemBack(), 'Back pops the top route.');
$assert(DependencyTracker::epoch() === $epoch && DependencyTracker::isDirty($navigator), 'A Back that pops re-renders the navigator only.');
$navigator->push('top');
$navigator->interceptSystemBack(static fn (): bool => true);
$epoch = DependencyTracker::epoch();
$assert($navigator->dispatchSystemBack() && DependencyTracker::epoch() > $epoch, 'A Back an app interceptor consumed re-renders everything.');

echo "NAVIGATION_INVALIDATION_OK transition=scoped back=scoped interceptor=global\n";
