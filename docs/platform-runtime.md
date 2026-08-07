# Platform runtime

PAM Native's platform runtime provides bounded building blocks for typed
bridges, prioritized work, asynchronous UI, native-frame programs,
virtualization, recoverable background work, offline mutation delivery,
hardware-accelerated vector drawing, and server-driven UI.

Every status, type, kind, and opcode is an integer-backed enum. Every
serialized structure is versioned and bounded before allocation.

## Typed bridge IDL

`IdlCompiler` accepts a versioned JSON contract and generates fingerprinted
PHP, Kotlin, Swift, and Rust identifiers. Module, method, and field IDs are
append-only sequential integers beginning at `1`.

```php
$artifacts = IdlCompiler::compile(file_get_contents('bridge.pam-idl.json'));
```

Schemas are limited to 1 MiB, 256 modules, 256 methods per module, and 128
fields per method. The SHA-256 fingerprint can reject mismatched artifacts.

## Priority scheduler

```php
Scheduler::schedule(
    fn (CancellationToken $token) => $search->refresh($token),
    TaskPriority::UserBlocking,
    coalesce: 'search-results',
);
```

Immediate, user-blocking, render, normal, background, and idle work runs in
priority order. Coalescing cancels obsolete work. Draining respects a frame
budget and callbacks receive cooperative cancellation.

## Async resources and Suspense

```php
$products = new AsyncResource(
    fn (CancellationToken $token) => $repository->products($token),
    key: 'products',
);
$products->load(TaskPriority::UserBlocking);

return Suspense::make(
    $products->value(),
    content: fn (array $items) => ProductGrid::make($items),
    fallback: ProductSkeleton::make(),
    failure: fn (AsyncValue $state) => ErrorCard::make($state->message),
);
```

Loading may retain stale data; replacement loads cancel obsolete work.

## Worklet bytecode

```php
use Pam\Native\UI\Animated;
use Pam\Native\UI\Text;
use Pam\Native\Worklets\Worklet;
use Pam\Native\Worklets\WorkletTarget;

return Animated::worklet(
    Text::make('Runs without PHP on every frame'),
    Worklet::input()->interpolate(0, 300, 0, 1)->clamp(0, 1),
    WorkletTarget::Opacity,
    durationMs: 300,
)->iterations(3);
```

Worklets are data-only numeric programs. They cannot call PHP, allocate
objects, perform I/O, or access global state. Programs are limited to 256
instructions and all values must remain finite. Android evaluates PNW1 on its
native frame animator and iOS evaluates it from `CADisplayLink`; PHP is not
entered between frames. Opacity, X/Y translation, scale, and rotation targets
are available, and the numeric input is elapsed time in milliseconds.

## Advanced virtualization

`VirtualizedList`, `VirtualGrid`, and `SectionList` use Rust layout and platform
recycling. They support heterogeneous retained cells, stable keys, authored or
estimated extents, grids, horizontal/inverted presentation, initial index,
bounded prefetch, scroll events, and end-reached delivery.

```php
return VirtualizedList::make(...$cells)
    ->estimatedRowHeight(72)
    ->prefetch(8)
    ->inverted()
    ->onEndReached($loadOlder, threshold: 0.25);
```

Scrolling and recycling do not require a PHP callback per frame.
When an Android route is retained off-screen, aggregate visibility restoration
also repairs visible holders whose rich subtree was released. This covers route
transitions that do not trigger a window-level visibility callback.

## Recoverable background jobs

`BackgroundJobs` stores jobs as idempotent offline mutations. Persist its
snapshot with PAM storage or Nitro and restore it after process death.

```php
$jobs->register('messages.sync', function (
    array $payload,
    CancellationToken $token,
): void {
    $sync->conversation((int) $payload['conversationId'], $token);
});

$jobs->dispatch(
    'messages.sync',
    uniqueKey: 'conversation:42',
    payload: ['conversationId' => 42],
);
```

`runReady()` schedules jobs at background priority. Cancellation and failures
use bounded exponential backoff. Native execution windows remain available
through `System\BackgroundTasks`.

## Offline mutation queue

`OfflineMutationQueue` provides idempotency-key deduplication; integer-backed
queued, sending, applied, retry, conflict, and failed states; exponential
backoff capped at one hour; 256 KiB payloads; 10,000 operations; and a bounded,
versioned snapshot.

## Hardware-accelerated Canvas

```php
return Canvas::make()
    ->roundedRectangle(8, 8, 120, 48, 12, 0xFF6750A4)
    ->circle(180, 32, 24, 0xFFFFD23F)
    ->line(8, 80, 220, 80, 4, 0xFF111111)
    ->style(new Style(height: 96));
```

Android renders the retained vector commands on a hardware layer; iOS renders
through UIKit/Core Graphics. Geometry must be finite. Payloads are limited to
10,000 commands and 1 MiB.

## Server-driven UI

Server-driven documents describe data, not executable PHP. Version `1`
allowlists view, column, row, text, button, image, scroll, and spacer nodes.

```php
$tree = ServerDrivenUi::render(
    $document,
    actions: fn (string $name): ?Closure => match ($name) {
        'offer.open' => $this->openOffer(...),
        default => null,
    },
);
```

Only locally resolved actions may run. Documents cannot name PHP classes or
functions. Styles use a numeric allowlist. Limits are 1 MiB, 10,000 nodes, 64
levels, and 16 KiB text. Signature verification, rollout, caching, and rollback
remain the responsibility of the trusted application update service.
