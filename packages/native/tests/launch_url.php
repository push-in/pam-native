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
$secondNavigator = $launchStack('launch-url-second');
NavigationContainer::make($secondNavigator)->linking();
$assert(
    $secondNavigator->current()->name === 'home',
    'A second container (hot reload) must not route the launch URL again.',
);
