<?php

declare(strict_types=1);

use Pam\Native\App;
use Pam\Native\Async\Promise;
use Pam\Native\Async\PromiseState;
use Pam\Native\Diagnostics\RuntimeError;
use Pam\Native\Diagnostics\RuntimeErrorPhase;
use Pam\Native\Internal\RuntimeSupervisor;

$assert = require __DIR__.'/arch_v2_bootstrap.php';

/** @var list<array{0: Throwable, 1: RuntimeError}> $reported */
$reported = [];
$listener = App::onError(static function (Throwable $error, RuntimeError $report) use (&$reported): void {
    $reported[] = [$error, $report];
});
RuntimeSupervisor::reset();

// A throw inside then() skips the following then() callbacks and reaches the next catch(), as in JS.
$source = new Promise();
$steps = [];
$caught = null;
$source
    ->then(static function (int $value) use (&$steps): int {
        $steps[] = "first:{$value}";
        throw new DomainException('boom');
    })
    ->then(static function () use (&$steps): void {
        $steps[] = 'skipped';
    })
    ->catch(static function (Throwable $error) use (&$caught): string {
        $caught = $error;

        return 'recovered';
    })
    ->then(static function (string $value) use (&$steps): void {
        $steps[] = $value;
    });
$source->resolve(1);
$assert(
    $steps === ['first:1', 'recovered'] && $caught instanceof DomainException && $caught->getMessage() === 'boom',
    'A then() callback that throws must reject the chain until the next catch(): '.json_encode($steps),
);

// A catch() that throws passes the new error on; finally() keeps the reason.
$second = null;
$finally = 0;
Promise::rejected(new RuntimeException('first'))
    ->catch(static fn (Throwable $e) => throw new LogicException('second', 0, $e))
    ->finally(static function () use (&$finally): void {
        $finally++;
    })
    ->catch(static function (Throwable $e) use (&$second): void {
        $second = $e;
    });
$assert(
    $second instanceof LogicException && $second->getPrevious()?->getMessage() === 'first' && $finally === 1,
    'catch() rethrows and finally() must reach the next catch().',
);
$assert($reported === [], 'Handled rejections must not be reported: '.count($reported));

// Unhandled: a rejection whose last promise has no catch() reaches App::onError() once.
$pending = new Promise();
$pending->then(static fn () => throw new UnexpectedValueException('forgotten catch'));
$pending->resolve('ok');
$assert(
    count($reported) === 1
        && $reported[0][0] instanceof UnexpectedValueException
        && $reported[0][1]->phase === RuntimeErrorPhase::UnhandledRejection
        && $reported[0][1]->fatal() === false,
    'An unhandled rejection must reach App::onError() once, as a non-fatal promise error: '.count($reported),
);
$assert(RuntimeSupervisor::safeMode() === false, 'Unhandled rejections must not count toward safe mode.');

// A dropped rejected promise is reported; a kept one is reported when released.
$reported = [];
Promise::rejected(new RuntimeException('dropped'));
$kept = Promise::rejected(new RuntimeException('kept'));
$assert(count($reported) === 1 && $reported[0][0]->getMessage() === 'dropped', 'A dropped rejected promise must be reported.');
$late = null;
$kept->catch(static function (Throwable $e) use (&$late): void {
    $late = $e->getMessage();
});
unset($kept);
$assert(count($reported) === 1 && $late === 'kept', 'A catch() attached later still handles the rejection.');

// Cancelled chains, rejections handled by Promise::all() consumers and adopted thenables are not reported.
$reported = [];
$cancelled = new Promise();
$cancelled->then(static fn () => null)->cancel();
unset($cancelled);
$allReason = null;
Promise::all([Promise::rejected(new RuntimeException('a')), Promise::rejected(new RuntimeException('b'))])
    ->catch(static function (Throwable $e) use (&$allReason): void {
        $allReason = $e->getMessage();
    });
$adopted = null;
Promise::resolved(1)
    ->then(static fn () => Promise::rejected(new RuntimeException('inner')))
    ->catch(static function (Throwable $e) use (&$adopted): void {
        $adopted = $e->getMessage();
    });
$assert($reported === [] && $allReason === 'a' && $adopted === 'inner', 'Handled and cancelled promises must stay silent: '.count($reported));
$assert((new Promise())->state() === PromiseState::Pending, 'Pending promises are never reported.');

App::offError($listener);
RuntimeSupervisor::reset();

echo "ARCH_V2_PROMISE_OK propagate catch-rethrow finally unhandled=onError non-fatal no-safe-mode\n";
