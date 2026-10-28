# Architecture v2

Architecture v2 makes a PAM Native app simple, typed and open, in the style of
Vue, React Native and Laravel. It is additive: code that does not migrate keeps
working. This page covers the component core: globals, `setup()`, lifecycle
hooks, reactivity, framework composables, `useResource()`, routing
(`routes.php`, middleware, typed navigation, deep links) and the HTTP client.

Every function on this page lives in the `Pam\Native` namespace and is
autoloaded by Composer (`"files"`), so one `use function` line imports it:

```php
use function Pam\Native\{signal, computed, watch, onMounted, onFocus, useResource};
```

## App layout

```
index.php          entry point and boot (globals, middleware, run)
routes.php         optional, Laravel-style routes
config/middleware.php  optional, middleware aliases shared with tests
src/
  Api/ Middleware/ Composables/ Screens/ Components/
```

Components are discovered by convention. When `App::components()` has not
been called, `App::run()` registers every `*.pam.php` under `<project root>/src`
and keeps the compiled cache in `<project root>/.pam-native/components`. The
project root is the nearest directory with `vendor/composer/installed.json`;
without one, it is the directory of the entry script. To use other paths,
call `App::components($path, $cache)` before `App::run()`. That explicit call
turns the convention off.

```php
use Pam\Native\App;
use function Pam\Native\{useColorScheme, useSafeAreaInsets, useNetwork};
use function App\Composables\useAuth;

require __DIR__.'/vendor/autoload.php';

App::globals([
    'scheme' => useColorScheme(),
    'insets' => useSafeAreaInsets(),
    'online' => useNetwork(),
    'auth'   => useAuth(),
]);
App::run(require __DIR__.'/routes.php');   // or App::run(HomeScreen::class)
```

## Globals

| API | Behaviour |
| --- | --- |
| `App::globals(array $values)` | Adds every pair; a repeated key overwrites. |
| `App::global(string $name, mixed $value, bool $replace = false)` | Registers one value. In a development build, registering a name twice without `replace: true` throws a clear `LogicException`. Production builds overwrite. |
| `App::forget(string $name)` | Removes the value. Readers re-render without it. |
| `Globals::get($name, $default)`, `Globals::has()`, `Globals::keys()`, `Globals::owner()` | Lower-level registry (`Pam\Native\Globals`). |

- Values can be anything: a signal, a composable, an object, an array, a scalar
  or a closure. Each entry is held in a signal, so replacing or forgetting a
  global re-renders whoever read it, and a reactive value such as
  `useColorScheme()` re-renders on its own changes.
- **Templates** read them as `$name`: `{{ $scheme.value }}`, `{{ $insets.top }}`,
  `p-if="$online.value"`, `{{ $count.value }}`. Lookup order is loop/slot data,
  then the component's own properties, then globals.
- **Components** read them as `$this->name` (`$this->auth->user->value`). A
  declared property of the same name wins.
- Names must be PHP identifiers. `this`, `data`, `scope`, `slot`, `props`,
  `state` and `GLOBALS` are reserved.

### Plugins

A plugin provider can contribute globals by implementing
`Pam\Native\Plugin\ProvidesGlobals`:

```php
final class AnalyticsPlugin implements PluginProvider, ProvidesGlobals
{
    public function register(): void {}
    public function boot(): void {}

    public function globals(): array
    {
        return ['analytics' => new Analytics()];
    }
}
```

Plugin globals are registered after `register()` and before `boot()`. The
application wins: a name already set with `App::globals()` is left alone. Two
plugins registering the same name is a development error.

## Components: `setup()`

`setup(...$params)` runs once each time the component mounts, before the
first render. It receives the route parameters by name, typed by its own
signature:

```php
final class ChatScreen extends Component
{
    public Signal $draft;
    public Resource $messages;

    public function setup(string $id, int $page = 1, ?Tab $tab = null): void
    {
        $this->draft = signal('');
        $this->messages = useResource(fn (?string $cursor) => Api::client()
            ->get("/chats/{$id}/messages", ['cursor' => $cursor])->json(),
            paginate: true, refreshOnFocus: true, cache: "chat:{$id}");

        onMounted(fn () => Analytics::screen('chat'));
        onUnmounted(fn () => Drafts::save($id, $this->draft->value));
    }
}
```

- **Parameter binding.** Path segments arrive as strings. They are converted
  only when the conversion is exact: `"42"` becomes `int 42`, `"true"` becomes
  `bool true`, and a backed-enum case is matched by its value. A parameter typed
  `RouteContext` receives the route itself. A missing required parameter or a
  value that does not convert throws a `LogicException` that names the
  component, the parameter and the value.
- **Where the parameters come from.** `ScreenFactory` (named routes) calls
  `Component::__pamRouteParams($params, $route)`. Screens built by the
  `Route` facade carry them in `Routing\RouteArguments`. Constructor parameters
  (the pre-v2 style) still work, and a screen may use both styles.
- **Ownership.** Everything `setup()` creates (watchers, composable
  subscriptions, resources, hooks) belongs to the component's
  `Signals\EffectScope`. Unmount releases it in reverse order. If the same
  instance mounts again, `setup()` runs again and subscribes again.
- **Order.** `boot()`, then `setup()`, then `mount()` (legacy), then the first
  render. After the first native commit, `attached()` and the `onMounted()`
  hooks run.

### Lifecycle hooks

| Function | Runs |
| --- | --- |
| `onMounted(Closure)` | Once per mount, after the first commit (immediately when already mounted). |
| `onUnmounted(Closure)` | At unmount, before the scope's subscriptions are released. |
| `onFocus(Closure)` | Each time the screen becomes visible (navigation focus). |
| `onBlur(Closure)` | Each time the screen is covered or left, and at unmount while focused. |

- The hooks must be called inside `setup()` or an `effectScope()`. Outside one,
  they throw `onMounted() must be called inside a component setup() or an
  effectScope()`.
- A component inside a routed screen follows that screen's focus, even when it
  is nested deep in the tree.
- A component that is not under a route (for example the root of
  `App::run(Home::class)` without routes) counts as focused while it is
  mounted.
- Routers report focus with `Component::__pamNavigationFocus(bool)`. The
  `Navigator` already does.

## Reactivity

| Function | Returns | Notes |
| --- | --- | --- |
| `signal(mixed $value)` | `Signals\Signal` | Read and write `->value` (`$count->value++`), or use `get()`/`set()`/`update()`. In templates: `$count.value`. |
| `computed(Closure)` | `Signals\ComputedSignal` | Cached and lazy. It recomputes only after a signal it read has changed. `->value` is read-only. |
| `watch($source, Closure $callback, bool $immediate = false)` | `Signals\Watcher` | `$source` is a signal, a computed value or a closure that reads signals. The callback receives `($next, $previous)` on every change and runs untracked. The watcher stops with the scope that created it, or with `dispose()`. |
| `effectScope(Closure)` | `Signals\EffectScope` | Runs code in its own scope (services, tests). `dispose()` releases everything created inside it. |

These functions wrap the existing `Signals` classes. `Signals::signal()`,
`Signals::computed()` and `Signals::effect()` remain available.

## Framework composables

All of them are reactive and push-based, with no polling. Each one shares a
single platform subscription per kind: ten screens that call `useKeyboard()`
hold one `Keyboard::onChange` listener. The subscription is released when the
last scope that uses it is disposed. A composable called outside any scope, for
example in `index.php` for `App::globals()`, lives as long as the app.

| Composable | Properties and methods | Wraps |
| --- | --- | --- |
| `useColorScheme()` | `value` (`'light'`/`'dark'`), `mode` (`'system'`/`'light'`/`'dark'`), `dark`, `appearance`, `preference`, `set('dark'\|'light'\|'system'\|AppearanceMode)`, `toggle()` | `Appearance` |
| `useSafeAreaInsets()` | `top`, `right`, `bottom`, `left` | `App::windowMetrics()` |
| `useWindowDimensions()` | `width`, `height`, `scale`, `fontScale`, `landscape`, `deviceClass`, `metrics` | `App::windowMetrics()` |
| `useFontScale()` | `value` (system scale), `effective` (body cap), `display` (display cap), `for(float $fontSize)` | window metrics and `TextScalePolicy` |
| `useKeyboard()` | `visible`, `height`, `durationMs`, `dismiss()` | `Keyboard::onChange` |
| `useAppState()` | `value` (`AppState`), `active`, `background` | `App::onStateChange` |
| `useNetwork()` | `online` (also `value`), `known`, `type` (`NetworkType`), `expensive` | `System\DeviceStatus::watch` (host pushes changes) |
| `useLocale()` | `value` (BCP 47 tag, `'pt-BR'`), `language` (catalog, `'pt'`), `region`, `device`, `known`, `overridden`, `set(?string)` | `System\DeviceInfo` locale + `I18n::use()` override |
| `useTimezone()` | `value` (IANA, `'America/Sao_Paulo'`), `device`, `known`, `overridden`, `zone()`, `now()`, `set(?string)` | `System\DeviceInfo` time zone + `I18n::useTimezone()` override |

- Properties are read-only. They also work in templates (`$insets.bottom`) and
  convert to an array with `toArray()`.
- `useNetwork()` assumes the app is online until the host sends its first
  status. Until then, `known` is `false`.
- `useLocale()` and `useTimezone()` share one `DeviceInfo` request. It is
  lazy: subscribing asks nothing; the first read of a value asks the host,
  and after that it is asked again each time the app returns to the
  foreground (the user may have changed the system language or zone). Until
  the host answers, `known` is `false`, the locale is the I18n source locale
  and the zone is PHP's default. `I18n::use('en-US')` (or `->set()`) and
  `I18n::useTimezone('Europe/Lisbon')` override the device, reactively;
  `null` follows the device again. `t()` keeps using `I18n::locale()`, so an
  app that wants the device language calls `I18n::use($locale->value)` or
  reads `$locale->language`.
- `Runtime::shutdown()` (hot reload, test teardown) stops every shared
  source, including the app-lifetime ones from `index.php`. The next
  composable call starts a fresh subscription.
- For window metrics, `Runtime::onWindowMetrics()` and `offWindowMetrics()`
  allow any number of listeners. `App::onDimensions()`, which keeps a single
  handler, is unchanged.

## `useResource()`

```php
$feed = useResource(fn (?string $cursor) => Api::client()->get('/feed', ['cursor' => $cursor])->json(),
    paginate: true, refreshOnFocus: true, lazy: false, cache: 'feed');
```

| Read | Meaning |
| --- | --- |
| `data` | The result, or the accumulated list when `paginate` is on. |
| `loading` | The first load, or a reload while there is no data. |
| `refreshing` | A reload while data is on screen. |
| `loadingMore`, `loadingNewer` | `more()` (older) or `more('newer')` is running. |
| `error` | The message of the last failure. Data is kept. |
| `hasMore` | An older page exists (next cursor). |
| `hasNewer` | A newer page exists (previous cursor), e.g. after opening around an item. |
| `stale`, `status` (`AsyncStatus`), `value` (`AsyncValue` for `<Suspense>`) | |

| Option | Meaning |
| --- | --- |
| `paginate` | The loader receives the cursor (`null` for the first page) and returns `['data' => [...], 'next' => ?string, 'prev' => ?string]`. Also accepted: `items`; `next_cursor`, `nextCursor`, `meta.next_cursor`; `previous`, `prev_cursor`, `previous_cursor`, `prevCursor`, `meta.prev_cursor`. The `items:`, `nextCursor:` and `previousCursor:` closures handle any other shape. An item already listed (same identity) is replaced in place by the server's version, never listed twice. |
| `cache: ['key' => 'feed', 'scope' => $userId, 'persist' => true, 'limit' => 100]` | Persistent, offline-first cache. On a cold start the last result shows at once (`stale`) while the network refreshes it; offline, it stays on screen with `error`. Entries are scoped (another account never sees them) and bounded: per scope 32 entries and 1 MiB on top of the native `Storage`, and a paginated list keeps its first `limit` items (default 100, without a cursor past the cut). It is written when the app goes to the background, or with `Resource::persistCache()`. |
| `key: fn () => [$auth->userId, $query->value]` | Reactive dependencies. When the value changes, requests in flight are cancelled, the cache entry switches to the new key (its cached result shows at once if there is one) and the resource reloads. The loader reads them as `$page->key`. A string `key:` is the legacy identity field. |
| `identity` | How items are matched (optimistic helpers and page merges): a field (`'id'`, the default), several fields (`['id', 'client_message_id']`: equal on any of them), or `fn ($a, $b): bool` (`$b` may be a key). |
| `around: $id` | Opens the first page around an item: the loader reads `$page->around` and returns both cursors. `reload(around: $id)` jumps elsewhere. |
| `refreshOnFocus` | Reloads each time the screen is focused again. The first focus is covered by the initial load. |
| `lazy` | Waits until something first reads the resource, instead of loading immediately. |
| `cache: 'key'` | Shows the last result for this key immediately (`stale`) and refreshes behind it. In-memory LRU of 64 entries and 4 MiB. `Resource::forgetCache()` clears everything, `forgetCache('feed')` one key, `forgetCache(scope: $userId)` one account (memory and disk), for example on logout. |

Methods:

- `reload(?$around)`: a reload with the same parameters in flight is joined;
  new parameters (`around`, changed `key:`) restart it. A reload cancels any
  `more()` in flight, so a page of the old list is never appended to the new
  one. The last request always wins: a superseded result is dropped.
- `more('older')` (default: appends the next page) and `more('newer')`
  (prepends the previous page). Both directions can run side by side; a
  repeated call in the same direction is joined, and nothing runs while the
  first page loads.
- The loader receives `(?string $cursor, CancellationToken $token, ResourcePage $page)`
  when paginated, `(CancellationToken $token, ResourcePage $page)` otherwise;
  declare only what you use. `ResourcePage` has `direction` (`'initial'`,
  `'older'`, `'newer'`), `cursor`, `around` and `key`.
- Optimistic UI: `prepend($item)`, `append($item)`, `replace($tmp, $real)`,
  `markFailed($tmp)` (an array item gets `_failed: true`; `isFailed($item)`),
  `remove($itemOrKey)`, and `update(fn ($data) => ...)` for any other shape.

How it runs:

- Requests run on the cooperative `Scheduler` through `AsyncResource`, never
  inside the event that asked for them.
- A loader may return a thenable (`then(onFulfilled, onRejected)`), such as the
  asynchronous HTTP client.
- When the owning scope is disposed, the scheduled request is cancelled, a
  thenable in flight is cancelled through its `cancel()`, and late results are
  ignored.

```php
public function send(string $text): void
{
    $tmp = ['id' => 'tmp-'.bin2hex(random_bytes(4)), 'text' => $text];
    $this->messages->prepend($tmp);
    Api::client()->post('/messages', ['text' => $text])
        ->then(fn ($r) => $this->messages->replace($tmp['id'], $r->json()))
        ->catch(fn () => $this->messages->markFailed($tmp['id']));
}
```

## Templates

The template compiler and its runtime evaluator (both code paths) resolve:

- `$name` from globals, when it is not a loop variable or a component
  property;
- `.value` (or `->value`) on a signal or computed value, read through
  `get()`, so the render tracks it;
- properties of composables and other objects with `__get` (`$insets.top`).

An undefined name now says where it was looked up: `Template expression $x is
undefined (not a loop variable, component property or App::global())`.

## Routing

Routes live in `routes.php`, Laravel style. The file returns nothing: every
`Route::` call is collected, and `App::run(require __DIR__.'/routes.php')`
boots them (`require` returns `1`; a file that returns the collection works
too). Apps without routes keep `App::run(HomeScreen::class)`.

```php
use Pam\Native\Route;

Route::screen('/login', LoginScreen::class)->name('login');

Route::middleware('auth')->group(function () {
    Route::tabs(function () {
        Route::screen('/chats', ChatsScreen::class)->name('chats')->icon('chatbubbles')->label('Conversas');
        Route::screen('/feed', FeedScreen::class)->name('feed')->icon('home');
    });
    Route::screen('/chat/{id}', ChatScreen::class)->name('chat')->gestures(false);
    Route::modal('/settings', SettingsScreen::class)->name('settings')->presentation('sheet');
});
```

| API | What it does |
| --- | --- |
| `Route::screen($path, Screen::class\|Closure)` | A stack route. Without `->name()`, the name comes from the path (`/settings/privacy` -> `settings.privacy`). |
| `Route::modal($path, ...)` | A stack route presented modally (`->presentation('sheet')` for a form sheet). |
| `Route::tabs(fn, name: 'tabs')` | Bottom tabs. Every `Route::screen()` inside is a tab; the group is one stack entry, so stack routes push over the tab bar. `->presentation()`, `->backBehavior()`. |
| `Route::group([...], fn)` | Shared attributes: `middleware`, `name` (prefix), `prefix` (path), `transition` (`NavigationTransition` or `[transition, ms]`), `gestures`, `presentation`, `orientation`, `options` (`ScreenOptionsPatch`, closure or `RoutePreset`), `errorBoundary`. A `RoutePreset` or patch alone is the options shorthand. |
| `Route::middleware('auth')->group(fn)`, `Route::name('settings.')`, `Route::prefix('/settings')` | Fluent groups. |
| `Route::layout(AppShell::class, fn)` | Root layout (Laravel/Expo layouts). The component receives the `Navigator` (or the `RouteNavigator`) by type and draws the stack inside, with gates, banners and hosts around or over it. `App::run()` mounts it. |
| `Route::errorBoundary(fn (Throwable $e, RouteContext $r, Closure $retry) => ...)` | Default boundary for every route without its own. |
| `Route::linking(false)`, `Route::persist()` | Opt out of system links; restore the persisted stack on cold start (off by default). The router listens to incoming links itself when routes have paths; an app that keeps its own `Linking::listen()` gets a development warning and should call `Route::open($uri)` from it, or declare `Route::linking(false)`. |

Chained on a route: `->name()`, `->middleware()`, `->icon()`, `->label()`,
`->title()`, `->gestures(bool)`, `->presentation('card'|'modal'|'fullScreen'|'transparent'|'sheet'|'contained')`,
`->orientation('portrait'|'landscape'|'all'|...)`, `->statusBar('light'|'dark', hidden: false)`,
`->transition(NavigationTransition, ms)`, `->sheet(detents)`, `->secure()`,
`->keepAlive(bool|fn (RouteContext $r) => !$r->has('id'))`,
`->unique(fn (RouteContext $r) => 'chat:'.$r->chatId)` (navigating to an entry
with the same identity goes back to it instead of pushing another),
`->alias('/group/{chatId}')` (repeatable; each alias is routed and becomes a
deep link), `->errorBoundary(fn)`, `->initial()` and `->deepLink(false)`.

The stack defaults are those of the pre-v2 `Route::stack()`: slide from right,
240 ms, and a fresh boot from the initial route. The initial route is the one
marked `->initial()`, else `/`, else the first tabs group, else the first
route. Its middleware runs at boot, so a signed-out user starts on `login`.

### Middleware

```php
use Pam\Native\{App, Middleware, Route};
use function Pam\Native\redirect;

final class Authenticate implements Middleware
{
    public function handle(Route $to, Closure $next): mixed
    {
        return Session::check() ? $next($to) : redirect('login');
    }
}

App::middleware(['auth' => Authenticate::class]);   // in index.php
```

To share the aliases with tests, keep them in `config/middleware.php` at the
project root (next to `routes.php`):

```php
// config/middleware.php
return ['auth' => Authenticate::class, 'role' => EnsureRole::class];
```

`App::run()` loads that file, and so does `Testing\routes()`. Aliases already
registered with `App::middleware([...])` keep their value; the file only fills
the missing ones. `App::middleware()` without arguments returns every
registered alias.

`$to` is the target route with its params (`$to->name()`, `$to->param('id')`,
`$to->is('settings.*')`). Return `$next($to)` to continue, `redirect('login')`
(`->reset()`, `->replace()`, `->push()`, `->with([...])`) to go elsewhere, or
`false` to stay. Aliases take arguments (`'role:admin,editor'` calls
`handle($to, $next, 'admin', 'editor')`), and closures work as middleware.
Middleware runs once per navigation: the stack guard and the tab bar reuse
that navigation's verdict. It also guards taps on the tab bar (a denied tab
stays unselected; a redirect navigates), links, the boot route and
`Route::refresh()` after login or logout, where it is evaluated again. Keep it
a check and do side effects in screens or composables.

### Navigating

```php
use function Pam\Native\{navigate, back};

navigate('chat', ['id' => $id]);
navigate(ChatScreen::class, id: $id);
navigate(Routes::chat(id: $id));          // generated, typed
back();
```

Options, on `navigate()` and `Route::navigate($route, $params, $mode, $transition, $merge, $duration)`:

```php
navigate('chat', ['id' => $id], mode: 'push', transition: NavigationTransition::Fade);
navigate('login', mode: 'reset');
navigate('chat', ['unread' => 0], merge: true);   // keeps the entry's id
```

- `mode`: `'navigate'` (default; React Navigation semantics: goes back to the
  route's entry when it is in the stack and updates its params, otherwise
  pushes), `'push'` (always a new entry), `'replace'` (swaps the top entry) or
  `'reset'` (clears the history). `RedirectMode` cases are accepted too.
- `transition` (`NavigationTransition`) and `duration` (ms) override the
  route's animation for this step.
- `merge: true` fills the params not given from the route's entry already in
  the stack, so required params need not be repeated.
- In the helper, `mode`, `transition`, `merge` and `duration` are reserved
  names: a route param called one of them goes in the `$params` array. The
  route compiler skips them, and does not report missing params when `merge:`
  is given.

`back()` is the system Back: the open child navigator (tabs, a nested stack)
goes back first, then this stack. A Back that the screen cancels (a
`BeforeRemove` listener calling `preventDefault()`, "leave without
saving?") is still consumed: `back()` returns `true` and the screen stays, so
the Back never falls through to closing the app. It returns `false` only when
there is nothing to go back to. The legacy `Navigator::pop()` differs: it
ignores child navigators and returns `false` when the screen cancels.
Params are validated against the screen before navigating: an unknown name, a
missing required parameter or a value that cannot become the declared type
throws. `Route::current()`, `Route::path('chat', ['id' => 5])`,
`Route::has()`, `Route::refresh()` and `Route::open($uri)` complete the API.

In templates:

```html
<Link route="chat" :params="{ id: chatId }"><Text>Open</Text></Link>
```

`<Link>` is a `Pressable` with the link accessibility role; `mode="replace"`
changes the stack action. `:params` takes a PHP array or a `{ key: value }`
object literal (bare names read template data; `{ id }` is shorthand for
`{ id: id }`). `Link::to('chat', ['id' => $id], ...$children)` is the PHP form.

### Screen parameters

URL and navigation params arrive typed in `setup()`:

```php
final class ChatScreen extends Component
{
    public function setup(string $id, int $unread = 0): void { /* ... */ }
}
```

Values from links are strings and are coerced to `int`, `float`, `bool` or a
backed enum; anything else fails with a clear message. Class-typed parameters
are services injected by type, not route params: `Navigator`, `RouteContext`
and `RouteNavigator` reach constructors, `setup()`, screen closures and the
layout. Pre-v2 screens with `__construct(Navigator $navigator, string $chatId)`
keep working unchanged. The router hands params to the component through
`RouteArguments::attach()` / `Component::__pamRouteParams()`, and screens
the stack cannot see directly (tabs, screens wrapped for a status bar) still
get `onFocus()` / `onBlur()`.

### Typed routes and deep links (compiler)

`pam-native-routes routes.php src/Navigation/Generated` (run by the PAM CLI
before every build when `routes.php` exists) loads the routes, reads each
screen's `setup()` (or constructor) signature, and:

1. generates `RouteName` (string-backed enum) and `Routes`, whose helpers
   carry the PHP types (`Routes::chat(string $id, int $unread = 0): TypedRoute`);
2. writes `.pam-native/routes.manifest.json` for the CLI;
3. checks every literal `navigate()`, `redirect()`, `Route::navigate()` and
   `<Link route=...>` in `src/`: an unknown route, an unknown or missing
   parameter, or a literal of the wrong type fails the build with
   `file:line: route chat requires parameter id.`

A path placeholder that is not a `setup()` parameter, or a tab that requires a
parameter, is a compile error too. `--check-only` runs only the checks.

Deep links come from the route paths. Declare once where they are reachable:

```json
"links": { "schemes": ["myapp"], "hosts": ["app.example.com"] },
"routes": { "file": "routes.php", "namespace": "App\\Navigation\\Generated", "output": "src/Navigation/Generated" }
```

The CLI adds one Android intent filter for each custom scheme, and one
`autoVerify` https filter per host and distinct static path prefix (`/chat/{id}`
and its aliases become `/chat/`, `/group/`). It also adds the schemes to
`ios.urlSchemes` and `applinks:<host>` to `ios.associatedDomains`, merged with
what `android`/`ios` already declare. At runtime the router routes
`myapp://chat/5` and `https://app.example.com/chat/5?unread=2` by path; only
query keys the screen declares are kept. `PAM_NATIVE_SKIP_ROUTES=1` makes the
CLI reuse the last manifest without running PHP.

### Migrating from routes.json and Route::stack

| Before (`@deprecated`) | Architecture v2 |
| --- | --- |
| `routes.json` + `pam-native-routes routes.json` + `Navigation/Generated/AppRoute` | `routes.php`; the compiler generates `RouteName` / `Routes` from it |
| `Routing\Route::stack('main', initial: ..., routes: fn)` | `Route::screen()`/`modal()`/`tabs()` at the top level of `routes.php`; `->initial()` |
| `Route::group(AppRoutePresets::chat(), fn () => Route::module(new ChatRoutes($runtime)))` | `Route::group(AppRoutePresets::chat(), fn)` with the module's routes inside (presets are accepted as is) |
| `Route::guard($guard, fn)` / `->guard()` | `Route::middleware('auth')->group(fn)` / `->middleware('auth')` |
| `->deepLink('/chat/{chatId}')->deepLink('/group/{chatId}')` | the route path plus `->alias('/group/{chatId}')` |
| `->identifyBy(fn)` | `->unique(fn)` |
| `$navigator->push(AppRoute::Chat, ['chatId' => $id])`, `Routes::chat(...)->push()` | `navigate('chat', ['chatId' => $id])`, `navigate(Routes::chat(chatId: $id))` |
| `Routing\Navigation::navigate()`, `RouteTarget` | `navigate()` / `TypedRoute` (middleware and validation included) |
| `new AppShell($navigator)` around `Route::stack()` | `Route::layout(AppShell::class, fn)` |
| Manual `android.deepLinks` / `ios.associatedDomains` lists per path | `links` in `pam-native.json`; entries are derived from the routes |

## HTTP

`Http::create()` returns an axios-style client over the native transport
(`Pam\Native\Http\Http`): requests run on the native networking stack and PHP
only receives the settled result.

```php
use Pam\Native\Http\{Http, HttpClient, HttpError, Request};

final class Api
{
    public static function client(): HttpClient
    {
        static $client;

        return $client ??= Http::create(baseUrl: 'https://api.example.com', timeout: 15_000, retry: 2)
            ->onRequest(fn (Request $r) => $r->withToken(Session::token()))
            ->onError(fn (HttpError $e) => $e->status === 401 ? Session::expire() : null);
    }
}

Api::client()->get('/feed', ['cursor' => $cursor])->json()
    ->then(fn (array $feed) => $this->feed->set($feed))
    ->catch(fn (HttpError $e) => $this->error->set($e->json('message', 'Falha na rede.')));
```

- `Http::create(baseUrl, headers, timeout, retry, retryDelay)`. The client is
  immutable: `onRequest()`, `onResponse()`, `onError()`, `withHeaders()`,
  `withBaseUrl()`, `withTimeout()` and `withRetry()` return a copy.
- `get($url, $query)`, `delete($url, $query)`, `post/put/patch($url, $data)`
  (arrays and objects as JSON, strings as is) and `request()`.
- `multipart($url, fn (MultipartRequest $form) => $form->field(...)->file(...), $progress)`
  and `upload($url, $privatePath, $headers, $progress)` keep the native
  transfers with byte progress; the interceptors apply to them too.
- They return a `PendingResponse`: a `Thenable` (`then()`, `catch()`,
  `finally()`, `cancel()`) of `Response`, plus `json($key)` and `text()`
  shortcuts that return a Thenable of the decoded body.
- `Request` is immutable: `withToken()`, `withHeader()`, `withHeaders()`,
  `withoutHeader()`, `withQuery()`, `withJson()`, `withBody()`,
  `withTimeout()` and `withContext()` (values interceptors share).
- `Response`: `status`, `headers` (lower-case), `body`, `json($dotKey)`,
  `header()`, `ok()`.
- `HttpError` (rejection reason): `status`, `request`, `response`,
  `isNetworkError()`, `isTimeout()`, `isClientError()`, `isServerError()` and
  `json($key)`. Statuses >= 400 and transport failures reject.

Interceptors run in registration order and may be asynchronous (return a
Thenable). `onRequest` returns the new `Request` or null. `onResponse` returns
a `Response` or null. `onError` returns a `Response` to recover, a `Request` to
resend it (for example after refreshing a token; at most 3 resends, and every
resend goes through `onRequest` again), or null to let the rejection continue.

Retry applies only to idempotent requests (GET, PUT, DELETE), after a
transport failure, 408, 429 or 5xx. It waits `retryDelay * 2^n` or the
`Retry-After` header, capped at 60 s, on a native timer (no polling).

`useResource()` accepts the PendingResponse directly and cancels it when its
scope unmounts:

```php
$feed = useResource(fn (?string $cursor) => Api::client()->get('/feed', ['cursor' => $cursor])->json(),
    paginate: true, refreshOnFocus: true);
```

`Pam\Native\Async\Promise` is the Thenable behind it (`resolved()`,
`rejected()`, `all()`, `from()`). It is single-threaded: callbacks run when the
native result arrives. Cancelling a derived promise cancels its source, which
is what a request chain needs.

Errors behave as in JavaScript:

- An exception thrown inside a `then()`, `catch()` or `finally()` callback
  rejects the derived promise. The following `then()` callbacks are skipped
  and the error reaches the next `catch()`.
- A rejection that nobody handles is reported. A promise is handled once
  `then()`, `catch()` or `finally()` was called on it. When a rejected promise
  is released without a handler (the end of a chain with no `catch()`, or
  `Promise::rejected()` dropped), its reason goes to every `App::onError()`
  listener with the phase `RuntimeErrorPhase::UnhandledRejection` (`'promise'`,
  never fatal), and to the error overlay in development builds. A `catch()`
  attached later, while the promise is still referenced, still handles it.
  Cancelled promises and `TaskCancelledException` reasons are not reported,
  and unhandled rejections do not count toward the runtime's safe mode.

## Testing

`Pam\Native\Testing` mounts components and routes through the real runtime
(lifecycle, composables, middleware, focus) without a device. Tests never call
the internal `__pamSetup()`, `__pamMounted()` or `__pamDisposeResources()`.

```php
use Pam\Native\Testing;

// A component: props by name, route params for setup().
$chat = Testing\mount(ChatScreen::class, ['title' => 'Room'], params: ['id' => '1']);
$chat->instance->draft->value = 'oi';
$chat->render();                      // another render pass; returns its Element
assert(str_contains($chat->text(), 'oi'));
$chat->blur();                        // onBlur(), as when another screen covers it
$chat->focus();                       // onFocus()
$chat->unmount();                     // onUnmounted(); setup() scope, own() and timers released

// The device: scheme, insets, window, keyboard, locale and time zone.
Testing\environment(scheme: 'dark', insets: ['top' => 24, 'bottom' => 34], keyboard: 280,
    window: ['width' => 390, 'height' => 844], locale: 'pt-BR', timezone: 'America/Sao_Paulo');

// The app's routes, with the aliases from config/middleware.php.
$app = Testing\routes(__DIR__.'/../routes.php')->open('/chat/1');
assert($app->current()->routeName() === 'chat' && $app->screen()->id === '1');
$app->navigate('home')->back();

Testing\reset();                      // teardown: unmount all, shut the runtime down
Testing\routes()->open('/chat/2');    // routes and aliases survive reset()
```

| API | What it does |
| --- | --- |
| `Testing\mount($class\|$instance, $props = [], ?array $params = null)` | Accepts an instance made with `new` (no registry needed for components with `render()`; a template-only `.pam.php` component missing from the registry triggers the `src/` convention discovery, as `App::run()`). Mounts the component: `boot()`, `setup()` (with `$params` as route params), `mount()`, the first render, `onMounted()`. With `$params` the component is a screen and also gets `onFocus()`, as from the router; without, it counts as focused while mounted. Returns a `Testing\Mounted` handle. Several roots can be mounted at once. |
| `Mounted` | `instance`, `render()` (a render pass; returns the root's `Element`), `element()`, `text()` (every `Text` joined by a space), `focus()`, `blur()`, `unmount()`, `mounted()`. |
| `Testing\routes($routesFile\|require result\|RouteCollection\|null, ?array $middleware = null)` | Builds and activates the `RouteNavigator`, so `navigate()`, `back()` and `Route::current()` work, and mounts it. Given a path, the routes are registered from scratch. Middleware aliases: `$middleware`, then `config/middleware.php` next to the routes file (or in the project root). Returns a `Testing\RouteTester`. |
| `RouteTester` | `open($uri)` (path or deep link, middleware included), `navigate($route, $params)`, `back()`, `current()`, `path()`, `screen()` (the focused screen component), `text()` (of the focused screen), `render()`, `unmount()`, `navigator`. |
| `Testing\environment(scheme, insets, keyboard, window, locale, timezone)` | Sends what the host would: `scheme` (`'light'`/`'dark'`) becomes the system scheme with the `System` preference; `insets` and `window` (`width`, `height`, `scale`, `fontScale`) a window-metrics event; `keyboard` the settled keyboard (a height; `0` or `false` hides it, `true` is 300 pt); `locale` and `timezone` the `DeviceInfo` that `useLocale()`/`useTimezone()` read. Omitted arguments keep their value. Mounted components re-render. |
| `Testing\reset(bool $flushRoutes = false)` | Unmounts every root, calls `Runtime::shutdown()`, and clears composable sources and I18n overrides. Registered routes and middleware aliases are kept, so `Testing\routes()` (no argument) mounts them again; `flushRoutes: true` clears them too. |
| `Testing\unmount($handle\|$routeTester\|$component)` | Unmounts; a component that was never mounted, or was already unmounted or reset, is a no-op (so is `->unmount()` twice). |

`Testing\mount()` needs the runtime for itself: in a test that already called
`App::run()`, call `Testing\reset()` first. Without a host, frames are encoded
and dropped.

## Migration

| Before | Architecture v2 |
| --- | --- |
| `mount()` with `$this->own(...)` subscriptions | `setup()` with composables and `onMounted()`. Release is automatic. `mount()` still runs and is `@deprecated`. |
| `Appearance::onChange()` + a property + `Appearance::unsubscribe()` | `useColorScheme()` (or the `$scheme` global) |
| `App::windowMetrics()->safeAreaBottom` read once | `useSafeAreaInsets()->bottom`, which stays current |
| `Keyboard::onChange()` / `offChange()` | `useKeyboard()` |
| `App::onStateChange()` / `offStateChange()` | `useAppState()` |
| `DeviceStatus::watch()` / `unwatch()` for connectivity | `useNetwork()` |
| `AsyncResource` + `subscribe()` + manual state | `useResource()` |
| `effects()` / `watchers()` returning `Effect::watch(...)`, `Watch::value()` | `watch()` inside `setup()` (`@deprecated`) |
| `initialState()` + `$this->state->x` | `signal()` properties created in `setup()` (`@deprecated`) |
| Constructor route parameters | `setup(string $id)` (constructors still work) |
| `routes.json`, `Routing\Route::stack()`, `Navigator->push()` | `routes.php`, `navigate()` (see [Routing](#migrating-from-routesjson-and-routestack)) |
| `Http::get($url, $callback)` | `Http::create(...)->get($url)->then(...)` (the callback API still works) |
| App-specific singletons reached through statics | `App::globals()` |
| `__pamSetup()` / `__pamMounted()` / `__pamDisposeResources()` in tests | `Testing\mount()` and `->unmount()`; `Testing\routes()` for screens behind routes |
| Replacing the `$scheme` global to fake light/dark in tests | `Testing\environment(scheme: 'dark')` |
