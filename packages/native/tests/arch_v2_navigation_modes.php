<?php

declare(strict_types=1);

use Pam\Native\Navigation\NavigationEvent;
use Pam\Native\Navigation\NavigationEventType;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Route;
use Pam\Native\Testing;
use TestingApp\Middleware\Authenticate;

use function Pam\Native\back;
use function Pam\Native\navigate;

$assert = require __DIR__.'/arch_v2_bootstrap.php';
require_once __DIR__.'/../src/Routing/functions.php';
require_once __DIR__.'/../src/Testing/functions.php';
require_once __DIR__.'/Fixtures/TestingApp/bootstrap.php';

Testing\reset(flushRoutes: true);
Authenticate::$signedIn = true;
$app = Testing\routes(__DIR__.'/Fixtures/TestingApp/routes.php');
$stack = $app->navigator->root();
$names = static fn (): array => array_map(static fn (array $route): string => $route['name'].json_encode($route['params']), $stack->getState()['routes']);
$transition = static fn (): ?NavigationTransition => (new ReflectionProperty($stack, 'actionTransition'))->getValue($stack);

// mode: navigate (default) goes back to an entry already in the stack; push always adds one.
navigate('chat', ['id' => '1']);
navigate('chat', ['id' => '2'], mode: 'push', transition: NavigationTransition::Fade);
$assert($names() === ['home[]', 'chat{"id":"1"}', 'chat{"id":"2"}'] && $transition() === NavigationTransition::Fade, 'mode: push with a transition: '.json_encode($names()));
navigate('home');
$assert($names() === ['home[]'], 'mode: navigate returns to the entry in the stack: '.json_encode($names()));
navigate('chat', id: '1');

// merge keeps the entry's params that are not given.
navigate('chat', ['unread' => 3], merge: true);
$app->render();
$assert($names() === ['home[]', 'chat{"id":"1","unread":3}'] && $app->screen()?->unread === 3 && $app->screen()?->id === '1', 'merge: true must keep id: '.json_encode($names()));

// replace and reset, also through Route::navigate with the enum or the string.
Route::navigate('chat', ['id' => '9'], 'replace', NavigationTransition::None);
$assert($names() === ['home[]', 'chat{"id":"9"}'] && $transition() === NavigationTransition::None, 'mode: replace swaps the top entry.');
navigate('chat', ['id' => '5'], mode: \Pam\Native\Routing\RedirectMode::Reset);
$assert($names() === ['chat{"id":"5"}'], 'mode: reset clears the history.');
$badMode = false;
try {
    navigate('home', mode: 'jump');
} catch (InvalidArgumentException) {
    $badMode = true;
}
$assert($badMode, 'Unknown modes must be rejected.');

// back(): the system Back. A Back the screen cancels is consumed (true) and stays;
// the legacy Navigator::pop() reports false in that case.
navigate('home');
navigate('chat', ['id' => '6'], mode: 'push');
$guard = $stack->addListener(NavigationEventType::BeforeRemove, static function (NavigationEvent $event): void {
    $event->preventDefault();
});
$assert(back() === true && $names() === ['chat{"id":"5"}', 'home[]', 'chat{"id":"6"}'], 'A cancelled back() is consumed and stays.');
$assert($stack->pop() === false, 'Navigator::pop() returns false when the screen cancels.');
$guard->unsubscribe();
$assert(back() === true && $names() === ['chat{"id":"5"}', 'home[]'], 'back() pops one entry.');
$assert(back() === true && back() === false, 'back() is false only at the root.');

$app->unmount();
Testing\reset(flushRoutes: true);
Authenticate::$signedIn = false;

echo "ARCH_V2_NAVIGATION_MODES_OK push replace reset navigate transition merge back-consumed\n";
