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
$navigator->push('details', ['id' => 42]);
$navigator->pop();
$navigator->replace('details', ['id' => 84]);
$navigator->reset('home');
$navigator->navigate('details', ['id' => 42]);
$navigator->popTo('home');
$navigator->popToTop();
```

Route factories may remain zero-argument closures or receive a typed,
immutable `RouteContext`:

```php
use Pam\Native\Navigation\RouteContext;

Router::stack('home')
    ->route('home', fn () => $home)
    ->route('profile', fn (RouteContext $route) => new ProfileScreen(
        userId: $route->integer('id'),
        preview: $route->boolean('preview', false),
    ))
    ->deepLink('/profiles/{id}', 'profile')
    ->build();
```

`open('pam://app/profiles/42?preview=1')` resolves percent-encoded path
parameters and bounded scalar query values. `navigate()` returns to an existing
stack entry when possible instead of duplicating it. Persistence format v2
stores both route names and parameters; legacy name-only stacks still restore.
Route parameters are limited to 64 safe keys, scalar values, and 16 KiB
strings so untrusted deep links cannot inflate the retained tree.

Available transitions are `PlatformDefault`, `SlideFromRight`,
`SlideFromLeft`, `SlideFromBottom`, `Fade`, `FadeFromBottom`, `Scale`, and
`None`, plus `SlideFromTop`, `SharedAxisX`, and `SharedAxisY`. They are
integer-backed enums and are rendered natively with transform
and opacity. Android's disabled-animation accessibility setting is respected,
horizontal transitions automatically mirror in RTL layouts, and only the
incoming screen remains reachable by TalkBack during and after a transition.

## Adaptive tabs

Use `Router::tabs()` for one to five top-level destinations:

```php
$tabs = Router::tabs('overview')
    ->tab('overview', 'Overview', $overview, $overviewIcon)
    ->tab('orders', 'Orders', $orders, $ordersIcon, badge: '3')
    ->appearance(0xFF0F172A, 0xFF60A5FA, 0xFF94A3B8, 0xFF1E293B)
    ->persistence('main-tabs')
    ->build();
```

Adaptive presentation uses a bottom bar below 840 dp and a navigation rail at
or above that width. Only the selected native screen is mounted to minimize
cold-start work; PHP component instances and `State` preserve each
destination's state. Selection exposes tab semantics and triggers native
selection haptics.
