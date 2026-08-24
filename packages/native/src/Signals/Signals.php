<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

use Closure;
use Pam\Native\Internal\Runtime;

final class Signals
{
    private static int $batchDepth = 0;
    private static bool $renderPending = false;

    private function __construct()
    {
    }

    /** @template T @param T $value @return Signal<T> */
    public static function signal(mixed $value): Signal
    {
        return new Signal($value);
    }

    /** @template T @param Closure(): T $compute @return ComputedSignal<T> */
    public static function computed(Closure $compute): ComputedSignal
    {
        return new ComputedSignal($compute);
    }

    /** @param Closure(): (Closure(): void)|null $run */
    public static function effect(Closure $run): ReactiveEffect
    {
        return new ReactiveEffect($run);
    }

    /** @template T @param Closure(): T $callback @return T */
    public static function batch(Closure $callback): mixed
    {
        self::$batchDepth++;
        try {
            return $callback();
        } finally {
            self::$batchDepth--;
            if (self::$batchDepth === 0 && self::$renderPending) {
                self::$renderPending = false;
                Runtime::requestRender();
            }
        }
    }

    /** @internal */
    public static function changed(): void
    {
        if (self::$batchDepth > 0) {
            self::$renderPending = true;
            return;
        }
        Runtime::requestRender();
    }
}
