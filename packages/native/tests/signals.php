<?php

declare(strict_types=1);

use Pam\Native\Signals\Signals;

$count = Signals::signal(1);
$price = Signals::signal(4);
$totalRuns = 0;
$total = Signals::computed(static function () use ($count, $price, &$totalRuns): int {
    $totalRuns++;
    return $count->get() * $price->get();
});

$assert($total->get() === 4, 'Computed signals must evaluate their dependencies.');
$assert($total->get() === 4 && $totalRuns === 1, 'Computed signals must cache clean values.');

$observed = [];
$effect = Signals::effect(static function () use ($total, &$observed): Closure {
    $observed[] = $total->get();
    return static function () use (&$observed): void {
        $observed[] = -1;
    };
});

$count->set(2);
$assert(
    $total->get() === 8 && $totalRuns === 2 && $observed === [4, -1, 8],
    'Signal writes must invalidate computed values and rerun dependent effects.',
);

$changes = [];
$subscription = $price->subscribe(
    static function (int $next, int $previous) use (&$changes): void {
        $changes[] = [$previous, $next];
    },
);
Signals::batch(static function () use ($count, $price): void {
    $count->update(static fn (int $value): int => $value + 1);
    $price->set(5);
});
$assert(
    $total->get() === 15 && $changes === [[4, 5]],
    'Batched writes must preserve typed subscriptions and the final computed value.',
);
$price->unsubscribe($subscription);
$effect->stop();

$assert(
    \Pam\Native\AdaptiveLayout::classify(
        new \Pam\Native\WindowMetrics(599.0, 900.0, 2.0),
    ) === \Pam\Native\DeviceClass::Compact
    && \Pam\Native\AdaptiveLayout::classify(
        new \Pam\Native\WindowMetrics(700.0, 900.0, 2.0),
    ) === \Pam\Native\DeviceClass::Medium
    && \Pam\Native\AdaptiveLayout::classify(
        new \Pam\Native\WindowMetrics(900.0, 700.0, 2.0),
    ) === \Pam\Native\DeviceClass::Expanded,
    'Adaptive layout classes must use deterministic compact, medium and expanded breakpoints.',
);
