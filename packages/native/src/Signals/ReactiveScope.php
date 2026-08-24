<?php

declare(strict_types=1);

namespace Pam\Native\Signals;

use Closure;
use LogicException;

/** @internal */
final class ReactiveScope
{
    /** @var list<ReactiveObserver> */
    private static array $stack = [];

    private function __construct()
    {
    }

    public static function track(ReactiveSource $source): void
    {
        $index = array_key_last(self::$stack);
        if ($index === null) {
            return;
        }
        self::$stack[$index]->dependOn($source);
    }

    public static function evaluate(ReactiveObserver $observer, Closure $callback): mixed
    {
        self::$stack[] = $observer;
        try {
            return $callback();
        } finally {
            if (array_pop(self::$stack) !== $observer) {
                self::$stack = [];
                throw new LogicException('Reactive observer stack is corrupted.');
            }
        }
    }
}
