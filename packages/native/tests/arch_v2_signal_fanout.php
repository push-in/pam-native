<?php

declare(strict_types=1);

use Pam\Native\Signals\Signals;

use function Pam\Native\computed;
use function Pam\Native\signal;
use function Pam\Native\watch;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

// Several watchers on one signal: every watcher sees every change. Each effect
// detaches and re-attaches while it runs, so notifying from the live observer
// map made each watcher skip about half of the changes.
$source = signal(0);
$seen = [[], [], []];
$watchers = [];
foreach ([0, 1, 2] as $index) {
    $watchers[] = watch(static fn () => $source->get(), static function (int $next) use (&$seen, $index): void {
        $seen[$index][] = $next;
    });
}
foreach ([1, 2, 3, 4] as $value) {
    $source->set($value);
}
$assert($seen === [[1, 2, 3, 4], [1, 2, 3, 4], [1, 2, 3, 4]], 'Every watcher of a signal must observe every change, got '.json_encode($seen).'.');

// computed() chains with several readers each: every reader stays current.
$base = signal(1);
$double = computed(static fn (): int => $base->get() * 2);
$quad = computed(static fn (): int => $double->get() * 2);
$chain = [[], [], [], []];
foreach ([0, 1] as $index) {
    $watchers[] = watch($double, static function (int $next) use (&$chain, $index): void {
        $chain[$index][] = $next;
    });
}
foreach ([2, 3] as $index) {
    $watchers[] = watch($quad, static function (int $next) use (&$chain, $index): void {
        $chain[$index][] = $next;
    });
}
foreach ([2, 3, 4] as $value) {
    $base->set($value);
}
$assert(
    $chain === [[4, 6, 8], [4, 6, 8], [8, 12, 16], [8, 12, 16]],
    'Every watcher of a computed chain must observe every change, got '.json_encode($chain).'.',
);

// A set inside a watcher: no infinite loop, and every watcher settles on the final value.
$counter = signal(0);
$mirror = signal(0);
$last = [null, null, null];
$watchers[] = watch($counter, static function (int $next) use ($mirror): void {
    $mirror->set($next * 10);
});
foreach ([0, 1, 2] as $index) {
    $watchers[] = watch(static fn (): int => $mirror->get() + $counter->get(), static function (int $next) use (&$last, $index): void {
        $last[$index] = $next;
    });
}
$bump = signal(0);
$watchers[] = watch($bump, static function (int $next) use ($bump): void {
    if ($next < 5) {
        $bump->set($next + 1);
    }
});
foreach ([1, 2, 3] as $value) {
    $counter->set($value);
    $assert($last === [$value * 11, $value * 11, $value * 11], 'Watchers must settle on the final value after a set inside a watcher, got '.json_encode($last).'.');
}
$bump->set(1);
$assert($bump->peek() === 2, 'A watcher that sets its own source must not re-enter itself (no loop).');
$ping = signal(0);
$pong = signal(0);
$watchers[] = watch($ping, static fn (int $next) => $pong->set($next + 1));
$watchers[] = watch($pong, static fn (int $next) => $ping->set($next + 1));
$ping->set(1);
$assert($ping->peek() === 3 && $pong->peek() === 2, 'Two watchers setting each other must stop instead of looping, got '.$ping->peek().'/'.$pong->peek().'.');

// batch() still coalesces the render request while every watcher fires.
$batched = signal(0);
$batchSeen = [[], []];
foreach ([0, 1] as $index) {
    $watchers[] = watch($batched, static function (int $next) use (&$batchSeen, $index): void {
        $batchSeen[$index][] = $next;
    });
}
Signals::batch(static function () use ($batched): void {
    $batched->set(1);
    $batched->set(2);
});
$assert($batchSeen === [[1, 2], [1, 2]], 'Watchers inside batch() must each see every change, got '.json_encode($batchSeen).'.');

// A watcher disposed by another watcher during the same notification does not run.
$shared = signal(0);
$late = [];
$second = null;
$watchers[] = watch($shared, static function () use (&$second): void {
    $second?->dispose();
});
$second = watch($shared, static function (int $next) use (&$late): void {
    $late[] = $next;
});
$shared->set(1);
$assert($late === [], 'A watcher disposed mid-notification must not run.');

foreach ($watchers as $watcher) {
    $watcher->dispose();
}
