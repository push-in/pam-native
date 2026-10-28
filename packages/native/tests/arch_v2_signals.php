<?php

declare(strict_types=1);

use Pam\Native\Signals\ComputedSignal;
use Pam\Native\Signals\Signal;

use function Pam\Native\computed;
use function Pam\Native\effectScope;
use function Pam\Native\signal;
use function Pam\Native\watch;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

// signal(): ->value reads and writes, ++ works through __get/__set.
$count = signal(1);
$count->value++;
$assert($count instanceof Signal && $count->value === 2 && $count->get() === 2, 'signal() must expose ->value as a read/write alias.');

// computed(): cached, recomputed only after a dependency changed.
$runs = 0;
$double = computed(static function () use ($count, &$runs): int {
    $runs++;

    return $count->value * 2;
});
$assert($double instanceof ComputedSignal && $double->value === 4 && $double->value === 4 && $runs === 1, 'computed() must cache its value.');
$count->value = 5;
$assert($double->value === 10 && $runs === 2, 'computed() must recompute after a dependency changed.');
$threw = false;
try {
    $double->value = 3;
} catch (LogicException) {
    $threw = true;
}
$assert($threw, 'A computed value must be read-only.');

// watch(): (next, previous), only on change, immediate option, dispose stops it.
$seen = [];
$watcher = watch($count, static function (int $next, ?int $previous) use (&$seen): void {
    $seen[] = [$next, $previous];
});
$count->value = 5;
$count->value = 6;
$watcher->dispose();
$count->value = 7;
$assert($seen === [[6, 5]], 'watch() must report changes with the previous value and stop when disposed.');
$immediate = [];
$stop = watch(static fn (): int => $count->value + 1, static function (int $next, ?int $previous) use (&$immediate): void {
    $immediate[] = [$next, $previous];
}, immediate: true);
$count->value = 8;
$stop->dispose();
$assert($immediate === [[8, null], [9, 8]], 'watch() on a closure must track what it reads and honour immediate.');

// watch() inside a scope is stopped with the scope (no leak).
$scoped = [];
$scope = effectScope(static function () use ($count, &$scoped): void {
    watch($count, static function (int $next) use (&$scoped): void {
        $scoped[] = $next;
    });
});
$count->value = 10;
$assert($scope->size() === 1, 'watch() inside a scope must be owned by it.');
$scope->dispose();
$count->value = 11;
$assert($scoped === [10] && $scope->disposed(), 'Disposing the scope must stop its watchers.');

echo "ARCH_V2_SIGNALS_OK value computed watch scope-dispose\n";
