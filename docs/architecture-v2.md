# Architecture v2

Architecture v2 makes a PAM Native app simple, typed and open, in the style of
Vue, React Native and Laravel. It is additive: code that does not migrate keeps
working. This page covers the component core: globals, `setup()`, lifecycle
hooks, reactivity, framework composables and `useResource()`. Routes
(`Route::screen()`, middleware, `navigate()`) and the HTTP client are
documented in their own sections, added by the routing and HTTP work.

Every function on this page lives in the `Pam\Native` namespace and is
autoloaded by Composer (`"files"`), so one `use function` line imports it:

```php
use function Pam\Native\{signal, computed, watch, onMounted, onFocus, useResource};
```

## App layout

```
index.php          entry point and boot (globals, middleware, run)
routes.php         optional, Laravel-style routes
src/
  Api/ Middleware/ Composables/ Screens/ Components/
```

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

- Properties are read-only. They also work in templates (`$insets.bottom`) and
  convert to an array with `toArray()`.
- `useNetwork()` assumes the app is online until the host sends its first
  status. Until then, `known` is `false`.
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
| `loadingMore` | `more()` is running. |
| `error` | The message of the last failure. Data is kept. |
| `hasMore`, `stale`, `status` (`AsyncStatus`), `value` (`AsyncValue` for `<Suspense>`) | |

| Option | Meaning |
| --- | --- |
| `paginate` | The loader receives the cursor (`null` for the first page) and returns `['data' => [...], 'next' => ?string]`. `items`, `next_cursor`, `nextCursor` and `meta.next_cursor` are accepted too, and the `items:`/`nextCursor:` closures handle any other shape. Items already in the list are skipped (matched by `key`, default `'id'`). |
| `refreshOnFocus` | Reloads each time the screen is focused again. The first focus is covered by the initial load. |
| `lazy` | Waits until something first reads the resource, instead of loading immediately. |
| `cache` | Shows the last result for this key immediately (`stale`) and refreshes behind it. The cache is an in-memory LRU of 64 entries and 4 MiB; `Resource::forgetCache()` clears it, for example on logout. |

Methods:

- `reload()` and `more()`. Repeated calls while one is in flight are joined.
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
| Constructor route parameters | `setup(string $id)` (constructors still work) |
| App-specific singletons reached through statics | `App::globals()` |
