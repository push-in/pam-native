<?php

declare(strict_types=1);

use Pam\Native\Navigation\NavigationContainer;
use Pam\Native\Routing\Route;
use Pam\Native\System\Linking;
use Pam\Native\UI\Screen;
use Pam\Native\UI\Text;

// A deep-linked cold start routes the launch URL before the first frame.
$launchStack = static fn (string $name) => Route::stack(
    name: $name,
    initial: 'home',
    routes: static function (): void {
        Route::screen('home', static fn () => Screen::make(Text::make('Home')));
        Route::screen('list', static fn () => Screen::make(Text::make('List')))->deepLink('/list');
    },
);
(static function () {
    $taken = new ReflectionProperty(Linking::class, 'launchUrlTaken');
    $taken->setValue(null, false);
    $routed = new ReflectionProperty(Linking::class, 'routedLaunchUrl');
    $routed->setValue(null, null);
})();
putenv('PAM_NATIVE_LAUNCH_URL=bench://list');
$launchNavigator = $launchStack('launch-url-test');
NavigationContainer::make($launchNavigator)->linking();
$assert(
    $launchNavigator->current()->name === 'list'
        && getenv('PAM_NATIVE_LAUNCH_URL') === false
        && (new ReflectionProperty(Linking::class, 'routedLaunchUrl'))->getValue() === 'bench://list'
        && Linking::takeLaunchUrl() === null,
    'linking() must open the launch URL before the first render, once per process.',
);
// The launch route is simply there: no transition to finish, so no
// AnimationComplete round trip re-renders the stack after the first frame
// (the screen under it stays mounted below, like native-stack).
$launchHome = 0;
$launchRendered = (static function () use (&$launchHome): string {
    $navigator = Route::stack(
        name: 'launch-url-idle',
        initial: 'home',
        routes: static function () use (&$launchHome): void {
            Route::screen('home', static function () use (&$launchHome) {
                $launchHome++;

                return Screen::make(Text::make('Home'));
            });
            Route::screen('list', static fn () => Screen::make(Text::make('List')))->deepLink('/list');
        },
    );
    $navigator->openInitial('bench://list');
    $operation = new ReflectionProperty($navigator, 'operation');

    return json_encode([$navigator->current()->name, $operation->getValue($navigator)->name, count($navigator->toElement()->children)]);
})();
$assert(
    $launchRendered === json_encode(['list', 'Idle', 2]),
    'openInitial() must show the launch route without a transition ('.$launchRendered.').',
);
$secondNavigator = $launchStack('launch-url-second');
NavigationContainer::make($secondNavigator)->linking();
$assert(
    $secondNavigator->current()->name === 'home',
    'A second container (hot reload) must not route the launch URL again.',
);
