# Navigation

For the new root container, typed action/event API, screen options, canonical
paths, preload, retained route instances, predictive Back and top tabs, see
[Navigation Core 2](navigation-core-2.md).

Named routes are the default way to declare application navigation. Internal
routes are identities, not URLs; paths are only added when an external deep
link is required.

```php
use Pam\Native\Routing\Route;

$navigator = Route::stack(
    name: 'main',
    initial: 'home',
    routes: function (): void {
        Route::screen('home', HomeScreen::class);
        Route::screen('product', ProductScreen::class);
        Route::modal('filters', FiltersScreen::class)->sheet();
    },
);

App::run($navigator);
```

The Android back button and back gesture are connected automatically. They pop
the stack when another route exists and close the activity from the root route.
Use `handleSystemBack: false` on `Navigator`, or `->systemBack(false)` on
`Router`, only when the application provides a custom `App::onBack()` handler.

Components navigate without receiving a `Navigator` instance:

```php
$this->pushRoute('product', productId: 42);
$this->navigateRoute('home');
$this->replaceRoute('login');
$this->popRoute();
```

Named arguments are validated against the screen constructor when the route is
mounted:

```php
final class ProductScreen extends Component
{
    public function __construct(
        public readonly int $productId,
        public readonly bool $preview = false,
    ) {
    }
}
```

Deep links remain explicit and optional:

```php
Route::screen('profile', ProfileScreen::class)
    ->deepLink('/profiles/{userId}');
```

Routes may expose more than one external alias. Chain `deepLink()` calls and
all unique patterns will resolve to the same named screen:

```php
Route::screen('profile', ProfileScreen::class)
    ->deepLink('/u/{username}')
    ->deepLink('/profile/{username}');
```

## Route groups and per-screen motion

Route options are composable. Stack defaults are resolved first, followed by
outer-to-inner groups, the screen's fluent option layers and finally options
set dynamically by the mounted screen. A later sparse option changes only the
properties it declares.

```php
use Pam\Native\Navigation\NavigationGestureDirection;
use Pam\Native\Navigation\NavigationTransition;
use Pam\Native\Navigation\ScreenOptions;
use Pam\Native\Navigation\ScreenOptionsPatch;

$navigator = Route::stack(
    name: 'main',
    initial: 'home',
    options: new ScreenOptions(headerShown: false),
    routes: function (): void {
        Route::group(
            ScreenOptionsPatch::one('headerShown', true),
            routes: function (): void {
                Route::screen('profile', ProfileScreen::class)
                    ->transition(NavigationTransition::SlideFromRight, 240)
                    ->gesture(
                        direction: NavigationGestureDirection::Horizontal,
                        fullScreen: true,
                    );
            },
        );

        Route::modal('filters', FiltersScreen::class)
            ->transition(NavigationTransition::SlideFromBottom, 260)
            ->sheet(
                detents: [0.4, 1.0],
                grabber: true,
                cornerRadius: 24.0,
            );
    },
);
```

`transition()` accepts every native `NavigationTransition` and a per-route
duration from 0 through 2,000 ms. `gesture()` controls enablement, direction
and full-screen recognition. `presentation()`, `fullScreen()` and `sheet()` are
sparse layers and can safely be chained with `options()` in any order.

## Nested navigators

Stacks and tabs may be declared inline and registered as ordinary named route
content. Only the outermost navigator becomes the application navigation
scope; actions and Back bubble through the focused children.

```php
$root = Route::stack('root', 'main', static function (): void {
    Route::navigator(
        'main',
        Route::tabs('main-tabs', 'feed', static function (): void {
            Route::tab(
                'feed',
                Route::stack('feed-stack', 'feed-index', static function (): void {
                    Route::screen('feed-index', FeedScreen::class);
                    Route::screen('post', PostScreen::class);
                }),
                label: 'Feed',
            );
            Route::tab('account', AccountScreen::class, label: 'Account');
        }),
    );

    Route::modal('create', CreateScreen::class)->fullScreen();
});
```

Every child keeps independent state and history. A Back action is offered to
the focused child first and reaches the parent only when that child is already
at its root.

The lower-level `Router`, `Navigator`, `NavigationContainer`, action, event,
and `RouteContext` APIs remain available for custom navigation infrastructure
and are source-compatible with existing applications.

`open('pam://app/profiles/42?preview=1')` resolves percent-encoded path
parameters and bounded scalar query values. `navigate()` returns to an existing
stack entry when possible instead of duplicating it. Persistence format v3
stores route names and parameters with an integrity checksum; legacy v1/v2
stacks still restore.
Route parameters are limited to 64 safe keys, scalar values, and 16 KiB
strings so untrusted deep links cannot inflate the retained tree.

### Incoming application links

Let the root container own Android intents and iOS application links for its
entire mounted lifetime:

```php
$navigation = NavigationContainer::make($navigator)->linking(
    static function (string $url, bool $handled): void {
        // Optional analytics or fallback handling.
    },
);
```

The container consumes the cold-start URL, keeps one bounded listener armed for
warm-start URLs delivered through `singleTask` `onNewIntent()`, and releases it
automatically on unmount. `Linking::listenAndRoute()` remains available when a
non-container owner needs explicit subscription control.

On iOS, forward the initial and subsequent URLs from the application or scene
delegate:

```swift
PamLinking.captureInitial(launchURL)
PamLinking.open(incomingURL)
```

Custom schemes support both path-only matching and host-plus-path matching, so
`pushin://profile/david` can resolve a `/profile/{username}` pattern without
breaking existing schemes that intentionally ignore the host.

Available transitions are `PlatformDefault`, `SlideFromRight`,
`SlideFromLeft`, `SlideFromBottom`, `Fade`, `FadeFromBottom`, `Scale`, and
`None`, plus `SlideFromTop`, `SharedAxisX`, and `SharedAxisY`. They are
integer-backed enums and are rendered natively with transform
and opacity. Android's disabled-animation accessibility setting is respected,
horizontal transitions automatically mirror in RTL layouts, and only the
incoming screen remains reachable by TalkBack during and after a transition.
When the native transition completes, the outgoing route is released and the
host returns to the `Idle` operation before subsequent renders. A completed
pop is therefore never replayed against the remaining single-screen host.
During reconciliation, Android identifies a newly inserted route independently
from retained routes that are temporarily detached and reinserted for ordering.
The inserted route remains the transition target, and system Back then pops it
directly to the previously active route.

## Adaptive tabs

Use named tabs for one to five top-level destinations:

```php
$tabs = Route::tabs('main-tabs', initial: 'overview', routes: function (): void {
    Route::tab('overview', OverviewScreen::class, label: 'Overview', icon: $overviewIcon);
    Route::tab('orders', OrdersScreen::class, label: 'Orders', icon: $ordersIcon, badge: '3');
});
```

Adaptive presentation uses a bottom bar below 840 dp and a navigation rail at
or above that width. Only the selected native screen is mounted to minimize
cold-start work; PHP component instances and `State` preserve each
destination's state. Selection exposes tab semantics and triggers native
selection haptics.
