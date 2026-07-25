# Navigation

`Navigator` is Pam Native's typed stack navigator. It keeps the incoming and
outgoing screens mounted while Android animates them on the UI thread.

```php
use Pam\Native\App;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\Navigator;

$navigator = new Navigator(
    initialRoute: 'home',
    routes: [
        'home' => fn () => $homeScreen,
        'details' => fn () => $detailsScreen,
    ],
    persistenceKey: 'main',
    transition: NavigationTransition::PlatformDefault,
    transitionDurationMs: 240,
);

App::run($navigator);
```

The Android back button and back gesture are connected automatically. They pop
the stack when another route exists and close the activity from the root route.
Use `handleSystemBack: false` on `Navigator`, or `->systemBack(false)` on
`Router`, only when the application provides a custom `App::onBack()` handler.

For a declarative route table, use the fluent router:

```php
use Pam\Native\Navigation\Router;

$navigator = Router::stack('home')
    ->route('home', fn () => $homeScreen)
    ->route('details', fn () => $detailsScreen)
    ->transitions(NavigationTransition::SlideFromRight, 240)
    ->persistence('main')
    ->build();
```

Navigate from a component or event handler:

```php
$navigator->push('details');
$navigator->pop();
$navigator->replace('details');
$navigator->reset('home');
```

Available transitions are `PlatformDefault`, `SlideFromRight`,
`SlideFromLeft`, `SlideFromBottom`, `Fade`, `FadeFromBottom`, `Scale`, and
`None`. They are integer-backed enums and are rendered natively with transform
and opacity. Android's disabled-animation accessibility setting is respected,
and horizontal transitions automatically mirror in RTL layouts.
