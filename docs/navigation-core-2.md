# Navigation Core 2

Navigation Core 2 is PAM Native's retained, typed navigation runtime. PHP
declares routes and handles semantic actions, Rust reconciles the resulting
tree, and Kotlin/UIKit own transitions and interactive gestures. No animation
frame depends on PHP or a JavaScript bridge.

## Architecture

```text
NavigationContainer
  -> typed NavigationAction dispatcher
  -> versioned recursive state and canonical paths
  -> Navigator / TabNavigator / TopTabNavigator / DrawerNavigator
  -> retained Element tree and bounded PNT1/PNP1 patches
  -> PamNavigationHost on Android and iOS
```

Route, action, event, presentation, orientation, gesture direction and
transition discriminators are sequential integer-backed enums. Public route
parameters remain bounded scalar values and are validated before they enter
the retained tree or persistence.

## Root container and actions

```php
use Pam\Native\Navigation\NavigationAction;
use Pam\Native\Navigation\NavigationContainer;
use Pam\Native\Navigation\Router;

$stack = Router::stack('home')
    ->route('home', fn () => $home)
    ->route('profile', fn ($route) => new Profile($route->integer('id')))
    ->deepLink('/profiles/{id}', 'profile')
    ->build();

$navigation = NavigationContainer::make($stack)
    ->linking(fn (string $url, bool $handled) => $analytics->link($url, $handled))
    ->onReady(fn () => $analytics->ready())
    ->onStateChange(fn (array $state) => $analytics->screen($state))
    ->onUnhandledAction(fn ($action) => $logger->warning($action->toArray()));

$navigation->dispatch(NavigationAction::navigate('profile', ['id' => 42]));
```

Actions may carry `source` and `target` keys. A mismatched target is reported
without mutating state. `getRootState()`, `getCurrentRoute()`,
`getCurrentOptions()`, `canGoBack()` and `currentPath()` provide read-only
inspection without rendering the tree.

## Lifecycle and protected removal

```php
use Pam\Native\Navigation\NavigationEvent;
use Pam\Native\Navigation\NavigationEventType;

$subscription = $stack->addListener(
    NavigationEventType::BeforeRemove,
    function (NavigationEvent $event) use ($draft): void {
        if ($draft->isDirty()) $event->preventDefault();
    },
);

// Keep the subscription for as long as the listener is needed.
$subscription->unsubscribe();
```

The same `beforeRemove` contract protects programmatic pop, reset, replace,
deep-link history rewrites and system Back. State, focus, blur, parameter,
action and transition-start events use the same subscription API.

## Parameters, preload and canonical paths

```php
$stack->setParams(['preview' => true]);       // merge
$stack->replaceParams(['id' => 84]);          // replace
$stack->preload('profile', ['id' => 84]);     // instantiate before navigate
$url = $stack->currentPath();                 // /profiles/84
```

Preloaded and mounted routes are retained by route key. A normal reconciliation
does not invoke the route factory again. A parameter change invalidates only
the affected route instance.

## Typed screen options

```php
use Pam\Native\Navigation\NavigationPresentation;
use Pam\Native\Navigation\ScreenOptions;

Router::stack('home')->route(
    'checkout',
    fn () => new Checkout(),
    new ScreenOptions(
        title: 'Checkout',
        headerShown: true,
        presentation: NavigationPresentation::FormSheet,
        sheetAllowedDetents: [0.5, 1.0],
        sheetInitialDetentIndex: 1,
        sheetGrabberVisible: true,
    ),
);
```

Screen options validate bounds when the graph is built. The retained header
uses native PAM controls, safe-area insets and 44-point Back targets. Animation
and gesture options are applied directly to the platform navigation host.

## Top tabs

```php
$tabs = Router::topTabs('feed')
    ->tab('feed', 'Feed', fn () => new Feed())
    ->tab('following', 'Following', fn () => new Following())
    ->behavior(swipeEnabled: true, scrollEnabled: true, lazy: true)
    ->persistence('main-top-tabs')
    ->build();
```

Top-tab scenes mount lazily and are retained after first use. Swipe recognition
runs natively; PHP receives only the semantic completion that changes the
selected route.

## Platform performance contract

- Route transitions animate transform, scale and opacity on the UI thread.
- iOS implements every public transition enum, including shared-axis X/Y.
- Android 14+ predictive Back follows the system progress callback and commits
  only one semantic Back event to PHP.
- Reduced Motion and disabled platform animations bypass movement.
- Inactive transition routes are hidden from accessibility traversal.
- Only incoming and outgoing routes remain mounted during a stack transition.
- Route factories are retained by route key and are not called per frame or
  per unchanged reconciliation.

## Compatibility

Existing `Navigator`, `Router::stack()`, `Router::tabs()` and
`Router::drawer()` code remains source-compatible. Headers default to hidden
for existing PAM applications. New options are opt-in, and protocol enums stay
append-only.

## Coverage map

Navigation Core 2 covers the native-mobile portions of React Navigation's
common, stack/native-stack, bottom-tab, material-top-tab and drawer APIs:

| Area | PAM Native contract |
| --- | --- |
| Common actions | navigate, push, pop, goBack, replace, reset, popTo, popToTop, set/replace params and preload |
| Root inspection | ready, state change, unhandled action, current route/options/path/URL and canGoBack |
| Events | focus, blur, state, beforeRemove, transition, gesture, tab press/long press and drawer item press |
| State | typed recursive state, child bubbling, retained keys, history and checksummed persistence |
| Linking | URI prefixes, filter, query params, optional segments, terminal wildcards and canonical reverse paths |
| Navigators | retained native stack, adaptive bottom tabs/rail, swipeable top tabs and adaptive drawer |
| Native stack UI | header slots/search/large title, modal variants, form sheets, orientation and UI-thread transitions |
| Platform Back | focused-child recursion, transient interceptor, Android system Back and Android 14 predictive progress |

Web-only concepts such as DOM anchors and browser history are intentionally not
part of PAM's Android/iOS runtime. Configuration uses PHP's typed fluent API;
there is no separate JSX-style “static versus dynamic” split to maintain.

### iOS home-indicator integration

UIKit owns `prefersHomeIndicatorAutoHidden` on the containing
`UIViewController`. When `autoHideHomeIndicator` changes, PAM posts
`PamNativeHomeIndicatorAutoHide` with `userInfo["hidden"]` and calls
`setNeedsUpdateOfHomeIndicatorAutoHidden()` on the nearest controller. A
custom embedding controller should observe that notification, store the value,
and return it from `prefersHomeIndicatorAutoHidden`. The standalone PAM host
controller can apply the same contract directly.
