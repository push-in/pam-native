<?php

declare(strict_types=1);

use Pam\Native\Internal\Runtime;
use Pam\Native\Scheduling\Scheduler;
use Pam\Native\Scheduling\TaskPriority;
use Pam\Native\UI\Text;

$assert = require __DIR__.'/arch_v2_bootstrap.php';
Runtime::shutdown();
Scheduler::reset();
$order = [];
$label = 'initial';
$queueDuringRender = false;
Runtime::boot(static function () use (&$order, &$label, &$queueDuringRender): Text {
    $order[] = 'render:'.$label;
    if ($queueDuringRender) {
        $queueDuringRender = false;
        Scheduler::schedule(static function () use (&$order): void {
            $order[] = 'render-task';
        });
    }

    return Text::make($label);
});
Runtime::render();
Runtime::deferRendering(true);
$order = [];

// The first task exhausts a turn without changing UI. The native worker
// must still have a reason to resume: no second input/module event arrives.
Scheduler::schedule(static function () use (&$order): void {
    usleep(12_000);
    $order[] = 'first';
});
Scheduler::schedule(static function () use (&$order): void {
    $order[] = 'second';
});
$assert(!Runtime::flush() && $order === ['first'] && Scheduler::pending() === 1,
    'A task budget must leave the remaining task for another turn, not drain to exhaustion.');
$assert(method_exists(Runtime::class, 'hasPendingWork') && Runtime::hasPendingWork(),
    'A budget-exhausted task queue must keep the native worker runnable without another input event.');
$assert(!Runtime::hasPendingRender(), 'A continuation must not pretend unchanged UI is dirty.');
$turns = 0;
while (Runtime::hasPendingWork() && ++$turns <= 4) {
    Runtime::flush();
}
$assert($order === ['first', 'second'] && $turns === 1 && !Runtime::hasPendingWork(),
    'A pending continuation must complete and then let the worker sleep.');

// A response already requested by an input is committed before unrelated
// work. Only one scheduler budget is spent, even when there is a render.
$order = [];
$label = 'pressed';
Runtime::scheduleRender();
Scheduler::schedule(static function () use (&$order): void {
    usleep(12_000);
    $order[] = 'background';
}, TaskPriority::Background);
Scheduler::schedule(static function () use (&$order, &$label): void {
    $order[] = 'continuation';
    $label = 'loaded';
    Runtime::scheduleRender();
}, TaskPriority::Background);
$assert(Runtime::flush() && $order === ['render:pressed', 'background'],
    'The first visual response must precede background work, with one task budget per flush.');
$assert(Runtime::hasPendingWork() && Runtime::flush()
    && $order === ['render:pressed', 'background', 'continuation', 'render:loaded'],
    'The next bounded turn must render changes made by the remaining tasks.');
$assert(!Runtime::hasPendingWork() && !Runtime::flush(), 'An idle worker must do no extra render.');

// Tasks first discovered during the final render also need continuation.
$order = [];
Scheduler::schedule(static function () use (&$label, &$queueDuringRender): void {
    $label = 'new-component';
    $queueDuringRender = true;
    Runtime::scheduleRender();
});
Runtime::flush();
$assert($order === ['render:new-component'] && Runtime::hasPendingWork(),
    'A resource/task queued during a final render must keep a continuation.');
Runtime::flush();
$assert($order === ['render:new-component', 'render-task'] && !Runtime::hasPendingWork(),
    'A task created during render must finish without another native event.');

// Canceled work may be removed by a continuation but must never execute;
// resetting the runtime must drop the continuation as well as its queue.
$order = [];
$canceled = Scheduler::schedule(static function () use (&$order): void { $order[] = 'canceled'; });
$canceled->cancel();
Runtime::flush();
$assert($order === [] && !Runtime::hasPendingWork(), 'Canceled tasks must not keep an idle worker awake.');
Scheduler::schedule(static function () use (&$order): void { $order[] = 'old-request'; });
Runtime::shutdown();
$assert(!Runtime::hasPendingWork() && Scheduler::pending() === 0,
    'Shutdown/reload must discard every continuation of the previous request.');

echo "RUNTIME_SCHEDULER_CONTINUATION_OK bounded wakeup interaction-first nested cancel shutdown\n";
