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
its native nodes were committed. `resumed()`/`paused()` follow application
lifecycle. `cleanup()` is executed from a `finally` path after effect cleanups.
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
