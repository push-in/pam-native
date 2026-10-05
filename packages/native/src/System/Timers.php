<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class Timers
{
    private const MAX_DELAY_MS = 86_400_000;

    private static int $nextTimer = 1;

    /** @var array<int, array{callback: Closure, interval: ?int, failure: ?Closure}> */
    private static array $timers = [];

    private function __construct()
    {
    }

    /** @param Closure(): void $callback */
    public static function after(
        int $milliseconds,
        Closure $callback,
        ?Closure $failure = null,
    ): int {
        if ($milliseconds < 0 || $milliseconds > self::MAX_DELAY_MS) {
            throw new InvalidArgumentException(
                'Timer delay must be between 0 and 86,400,000 milliseconds.',
            );
        }

        return NativeModules::call(
            'timers',
            'after',
            ['milliseconds' => $milliseconds],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $callback();
            },
        );
    }

    /**
     * Runs $callback once after $milliseconds unless the handle is cancelled.
     *
     * @param Closure(): void $callback
     */
    public static function timeout(int $milliseconds, Closure $callback, ?Closure $failure = null): TimerHandle
    {
        return self::start($milliseconds, $callback, null, $failure);
    }

    /**
     * Runs $callback every $milliseconds (minimum 16 ms) until cancelled.
     *
     * @param Closure(TimerHandle): void $callback
     */
    public static function every(int $milliseconds, Closure $callback, ?Closure $failure = null): TimerHandle
    {
        if ($milliseconds < 16) {
            throw new InvalidArgumentException('Repeating timers need an interval of at least 16 milliseconds.');
        }

        return self::start($milliseconds, $callback, $milliseconds, $failure);
    }

    public static function cancel(TimerHandle|int $timer): void
    {
        $id = $timer instanceof TimerHandle ? $timer->id : $timer;
        if (!isset(self::$timers[$id])) {
            return;
        }
        unset(self::$timers[$id]);
        NativeModules::call('timers', 'cancel', ['timer' => $id], static fn (): null => null);
    }

    public static function active(int $timer): bool
    {
        return isset(self::$timers[$timer]);
    }

    private static function start(int $milliseconds, Closure $callback, ?int $interval, ?Closure $failure): TimerHandle
    {
        if ($milliseconds < 0 || $milliseconds > self::MAX_DELAY_MS) {
            throw new InvalidArgumentException(
                'Timer delay must be between 0 and 86,400,000 milliseconds.',
            );
        }
        $id = self::$nextTimer++;
        self::$timers[$id] = ['callback' => $callback, 'interval' => $interval, 'failure' => $failure];
        self::arm($id, $milliseconds);

        return new TimerHandle($id);
    }

    private static function arm(int $id, int $milliseconds): void
    {
        NativeModules::call(
            'timers',
            'after',
            ['milliseconds' => $milliseconds, 'timer' => $id],
            static function ($result) use ($id): void {
                $timer = self::$timers[$id] ?? null;
                if ($timer === null) {
                    return;
                }
                if ($result->status === ModuleResultStatus::Failure) {
                    unset(self::$timers[$id]);
                    if ($timer['failure'] !== null) {
                        ($timer['failure'])($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                if ($timer['interval'] === null) {
                    unset(self::$timers[$id]);
                    ($timer['callback'])();

                    return;
                }
                self::arm($id, $timer['interval']);
                ($timer['callback'])(new TimerHandle($id));
            },
        );
    }
}
