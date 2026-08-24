# Granular signals

Signals provide small, independently invalidated pieces of state without
introducing a second renderer or a JavaScript runtime. Reads performed while a
component renders are registered with the retained dependency graph; writes
dirty only consumers of that signal.

```php
use Pam\Native\Signals\Signals;

$count = Signals::signal(0);
$price = Signals::signal(12.50);
$total = Signals::computed(
    fn (): float => $count->get() * $price->get(),
);

$effect = Signals::effect(function () use ($total): Closure {
    Telemetry::gauge('cart.total', $total->get());

    return fn (): null => Telemetry::forget('cart.total');
});

Signals::batch(function () use ($count, $price): void {
    $count->set(3);
    $price->update(fn (float $value): float => $value * 0.9);
});
```

`computed()` discovers dependencies automatically and remains lazy and cached.
`effect()` reruns only after one of its observed sources changes, invoking the
previous cleanup first. Retain the returned effect and call `stop()` when an
owner with a shorter lifetime than the process is released.

`batch()` coalesces the render request produced by multiple writes. Nested
batches commit once at the outer boundary. Signal subscriptions are synchronous
and intended for small domain notifications; render work remains scheduled by
the display-aware PAM scheduler.
