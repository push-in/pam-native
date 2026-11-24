# Component runtime

Pam components combine typed immutable props, local reactive state, lifecycle
hooks, effects, boundaries and hierarchical context without copying component
state to the native bridge.

## Lifecycle

The normal order is:

```text
construct → boot → setup → mount → rendering → render → rendered
          → attached → resumed → effects
          → updating → propsChanged → rendering → render → updated effects
          → paused → unmount → effect cleanup → cleanup
```

`boot()`, `setup()` and `mount()` run once for an instance. `attached()` means
its native nodes were committed. `resumed()`/`paused()` follow the foreground:
only `AppState::Background` pauses. `inactive()`/`activated()` bracket
transient system UI (`AppState::Inactive`) while the component stays resumed. `cleanup()` is executed from a `finally` path after effect cleanups.
Keep `render()` pure.

## Typed props

Constructor promotion remains the prop declaration:

```php
final class Avatar extends Component
{
    public function __construct(
        #[Prop(required: true, min: 1)]
        public readonly int $userId,
        #[Prop(enum: AvatarShape::class)]
        public readonly AvatarShape $shape = AvatarShape::Circle,
    ) {}
}
```

`Prop` supports required, numeric min/max, backed-enum validation and
immutability. Immutable prop changes recreate the keyed component. Existing
unannotated mutable props retain their backward-compatible update behavior.

Use `updating($property, $next, $previous)`, `updated($property)` and the
batched `propsChanged(ComponentChanges $changes)` hooks to observe changes.

## Setup, state, computed and memo

```php
protected function initialState(): array
{
    return ['query' => '', 'selected' => null];
}

public function select(int $id): void
{
    $this->state->selected = $id;
}

#[Computed]
protected function hasSelection(): bool
{
    return $this->state->selected !== null;
}

public function render(): Renderable
{
    $rows = $this->memo(
        'rows',
        [$this->items],
        fn () => expensiveRows($this->items),
    );
    // ...
}
```

Local state accepts JSON-compatible values. Equal writes are ignored. Changes
invalidate computed values and request a retained-tree render. Memo values use
explicit dependency lists and are discarded during cleanup.

Template event handlers invalidate their owning component before invocation.
This also makes direct mutations of ordinary component properties observable in
the following render, including values nested in native-view property maps.

`setup()` is the right place to resolve stores and services. Do not start work
in the constructor.

## Update control

```php
public function shouldUpdate(ComponentChanges $changes): bool
{
    return $changes->changed('userId', 'theme');
}
```

When it returns false Pam retains the previous element tree and recursively
retains the lifecycle of its child component instances. A later local-state
change always invalidates this skip.

### Pure event handlers

Template event handlers mark their component dirty, so every native event
renders it. A handler that usually changes nothing (a timer-like progress
event, a scroll or viewport probe, a counter kept outside component state)
can opt out with `#[PureHandler]`:

```php
use Pam\Native\Attributes\PureHandler;

#[PureHandler]
public function progressed(float $current, float $duration): void
{
    $this->analytics->watched($current); // a service, not component state
}
```

```xml
<MediaPlayer source="$video" on:mediaProgress="progressed" />
```

After a pure handler the runtime renders only when something really changed:
the component's own state (`$this->label = ...`), a tracked store or signal it
read, or an explicit `requestRender()`/`markForRender()`. Otherwise the event
costs only the handler call: no tree walk, no encode, no patch. Element
closures accept the attribute too (`->onPress(#[PureHandler] fn () => ...)`),
also behind the SDK's payload wrappers. Writes to *another* component's
public properties are not detected from a pure handler; use a store/signal or
`markForRender()` for those.

### State snapshot and `#[Transient]` fields

After each render Pam takes a snapshot of every property the component
declares: public, protected and private, from traits and parent classes too.
Plain objects are captured two levels deep. Disposable handles count only by
presence (see below). On the next pass a component whose snapshot differs
renders again. Writes made outside a template handler are
caught this way, whether they come from a lifecycle hook (`rendered()`,
`attached()`, `resumed()`, `paused()`), a timer, a subscription or a promise
callback. `#[State]` has no part in this: it only marks what hot reload
preserves.

The snapshot can't tell which fields the template reads. PHP gives no hook on
reads of declared properties, and `render()` may reach any field through
methods and closures. A field used only for bookkeeping still costs a full
render each time it is written. Examples are a cache, a counter, a
subscription id, a "last synced at" timestamp or an "already done" flag. A hook that writes one on every resume, socket event or player
callback re-renders a large screen for nothing.

#### Disposable handles

A field whose value implements `Pam\Native\Disposable` holds a handle: a
`ComponentTimer`, an event `Subscription`, a `Debouncer` or `Throttler`, a
signal `Watcher` or `EffectScope`, a plugin's media player (`AudioPlayer` in
pam-native-audio). The snapshot keeps only the handle's class, never its
identity or its fields. This holds in a typed or untyped field and one level
down, in a plain object the component holds.

- **Handle state.** A timer firing, a player's progress tick, a subscription
  being cancelled: none of it renders the component.
- **Swapping handles.** Re-arming a timer from its own callback
  (`$this->timer = $this->timers()->timeout(...)`) or replacing a player with a
  new one of the same class doesn't render.
- **Null and back.** Setting the field from a handle to `null`, or from `null`
  to a handle, still renders, so a template that tests `$this->timer !== null`
  stays correct.
- **Reactive objects.** `Resource` is Disposable but holds template data. It
  keeps comparing by identity, so assigning a new resource renders.
- **Pure handlers.** Handle fields don't stop pure-handler inference, the same
  as `#[Transient]` fields.
- **Arrays of handles** compare by value as any array does: adding, removing
  or replacing a handle in the array renders. Mark such arrays `#[Transient]`.

A template that shows something a handle knows (a player's position, a
timer's `active()`) must copy it into a plain field from the handle's callback
(`->onProgress(fn (PlaybackProgress $p) => $this->position = $p->positionMillis)`)
or read it from a signal or store. Reading it through the handle shows a stale
value until something else renders the component.

Pam can't know whether the template reads a field, so this is the narrowest
rule that still drops handle churn. The stricter rule, leaving handle-typed
fields out of the snapshot entirely as if they were `#[Transient]`, also
passes the test suite. It was rejected because a template that tests whether
a handle field is `null` would then stop updating with no error.

#### `#[Transient]`

Mark other bookkeeping fields `#[Transient]` to leave them out of the snapshot:

```php
use Pam\Native\Attributes\Transient;

final class ChatScreen extends Component
{
    #[Transient]
    private ?int $networkSubscription = null;

    #[Transient]
    private int $lastResumeSyncAt = 0;

    // No attribute needed: a Disposable handle counts only by presence.
    private ?ComponentTimer $announcementTimer = null;

    public function resumed(): void
    {
        $this->lastResumeSyncAt = Clock::millis(); // no render
    }
}
```

How the attribute behaves:

- **Writes alone.** Writing a transient field never renders the component. The
  new value appears on the next render that something else causes.
- **Other render triggers.** Template event handlers that are not pure,
  `markForRender()`, `requestRender()`, prop updates from the parent, tracked
  signals and stores, and writes to any other field all render exactly as
  before.
- **Pure handlers.** A `#[PureHandler]` (or inferred-pure) handler that writes
  only transient fields renders nothing.
- **Objects.** Transient fields that hold objects, such as timers,
  subscriptions or debouncers, no longer stop pure-handler inference. That
  inference needs a snapshot made only of scalars, arrays and enums.
- **Where it applies.** The attribute works on any visibility, on promoted
  constructor properties and on trait properties. It combines with `#[State]`,
  which still preserves the field across hot reloads.

Only mark fields that `render()` and the template never read, directly or
through a method or computed value they call. A transient field that the
template does read shows a stale value until some other change renders the
component. Pam can't detect this case.

Without the attribute nothing changes. Every field written outside a handler
still renders the component, so existing apps keep their behavior. Pam doesn't
default to tracking only `#[State]` and props, because many screens keep
template-read data in plain private fields that callbacks and timers update.
Those screens would stop updating with no error.

## Effects and watchers

```php
protected function effects(): array
{
    return [
        Effect::watch(
            fn () => $this->userId,
            function (int $id): Closure {
                $subscription = $this->users->watch($id);
                return fn () => $subscription->cancel();
            },
        ),
    ];
}

protected function watchers(): array
{
    return [
        Watch::value(
            fn () => $this->state->query,
            fn ($current, $previous) => $this->search($current),
        ),
    ];
}
```

Effects run only after native attachment. Dependency changes run the previous
cleanup before the new effect. `Effect::once()` runs once. All cleanups are
attempted even if one throws.

## Owned resources and timers

```php
public function mount(): void
{
    EventBus::shared()->on('chat.read', fn (string $id) => $this->markRead($id), owner: $this);
    $this->own(Subscription::from(fn () => App::offStateChange($stateListener)));
    $this->timers()->every(30_000, fn () => $this->poll());
    $this->search = $this->timers()->debounce(300, fn (string $q) => $this->query($q));
}
```

Everything passed to `own()` (any `Disposable`) or created through
`timers()` is released after `unmount()`. Component timers pause in the
background by default (`TimerBackgroundPolicy::Suspend`) and are re-armed when
the component resumes. Subscribe in `mount()`, which runs again on remount. See
[App infrastructure](app-infrastructure.md).

## Error boundary and fallback

```php
public function failed(Throwable $error, ErrorContext $context): ?Renderable
{
    return ErrorCard::make($error->getMessage());
}

public function fallback(): ?Renderable
{
    return ProfileSkeleton::make();
}
```

An error thrown while rendering the component subtree first goes to `failed()`
and then `fallback()`. Return `null` to propagate it to the parent boundary.
The context contains the phase and recovery attempt.

## Provide and inject

```php
protected function provide(): array
{
    return [CheckoutContext::class => new CheckoutContext()];
}

$checkout = $this->inject(CheckoutContext::class);
```

Resolution walks only the component ancestry, making it suitable for flows,
forms and modal-local state that should not be global.

## Typed slots and events

```php
protected function slots(): array
{
    return [
        'header' => Slot::optional(),
        'content' => Slot::required(),
        'footer' => Slot::multiple(),
    ];
}

protected function events(): array
{
    return [UserSelected::class];
}

$this->emit(new UserSelected($id));
```

Slot cardinality is validated during component configuration. Typed events
implement `ComponentEvent`, declaring a stable listener name and payload.
String events remain supported.

## Component and native refs

Expose parent-callable methods explicitly:

```php
#[Expose]
public function reset(): void {}

$ref->call('reset');
```

`ComponentRef` holds a weak reference and rejects methods without `Expose`.
`NativeRef` is a lifecycle-safe operation handle used by native components and
plugins. Targets attach supported operations; `focus()`, `blur()`, `measure()`
and `scrollIntoView()` fail safely after detachment rather than operating on a
stale native view.
