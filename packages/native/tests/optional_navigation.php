<?php

declare(strict_types=1);

use Pam\Native\Navigation\Router;
use Pam\Native\Routing\Navigation;
use Pam\Native\Routing\Route;
use Pam\Native\UI\Screen;

$buildOptional = static fn () => Router::stack('home')->route('home', static fn () => Screen::make())->build();
$oldRoot = Navigation::root();
try {
    $innerOptional = null;
    $outerOptional = Route::customNavigator(static function () use ($buildOptional, &$innerOptional, $assert, $oldRoot) {
        $innerOptional = Route::customNavigator($buildOptional);
        $assert(Navigation::root() === $oldRoot, 'A nested optional navigator must not replace the root.');
        return $buildOptional();
    });
    $assert(Navigation::root() === $outerOptional && $innerOptional !== $outerOptional, 'Only the outer optional navigator attaches globally.');
    try {
        Route::customNavigator(static function (): never { throw new LogicException('fixture'); });
    } catch (LogicException $error) {
        $assert($error->getMessage() === 'fixture', 'Builder exceptions propagate.');
    }
    $afterError = Route::customNavigator($buildOptional);
    $assert(Navigation::root() === $afterError, 'An exception must restore optional navigation depth.');
    $lazyCalls = 0;
    $lazyScreen = Route::screenContent('home', static function () use (&$lazyCalls) { $lazyCalls++; return Screen::make(); });
    $assert($lazyCalls === 0 && $lazyScreen instanceof Closure, 'Optional screen factories remain lazy.');
    $lazyScreen();
    $assert($lazyCalls === 1, 'Lazy optional screen content resolves on demand.');
} finally {
    Navigation::attach($oldRoot);
}
echo "OPTIONAL_NAVIGATION_OK nested root exception lazy\n";
