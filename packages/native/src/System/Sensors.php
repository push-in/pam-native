<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;
use Pam\Native\Modules\NativeModules;
use RuntimeException;
use Pam\Native\SensorReading;
use Pam\Native\SensorType;

final class Sensors
{
    private static int $nextSubscription = 1;

    /** @var array<int, array{type: SensorType, callback: Closure, native: ?int, active: bool}> */
    private static array $subscriptions = [];

    private function __construct()
    {
    }

    /** @param Closure(?SensorReading): void $completed */
    public static function read(
        SensorType $type,
        Closure $completed,
        int $timeoutMs = 2_000,
    ): int {
        return Runtime::callNative(
            NativeOperation::SensorRead,
            Wire::map([
                'type' => $type->value,
                'timeoutMs' => max(100, min(10_000, $timeoutMs)),
            ]),
            static function (ModuleResultStatus $status, string $payload) use (
                $completed,
                $type,
            ): void {
                if ($status !== ModuleResultStatus::Success) {
                    $completed(null);

                    return;
                }

                $values = Wire::decodeMap($payload);
                $completed(new SensorReading(
                    type: $type,
                    x: self::number($values['x'] ?? null),
                    y: self::number($values['y'] ?? null),
                    z: self::number($values['z'] ?? null),
                    timestamp: is_int($values['timestamp'] ?? null)
                        ? $values['timestamp']
                        : 0,
                ));
            },
        );
    }

    /** @param Closure(SensorReading): void $callback */
    public static function watch(
        SensorType $type,
        Closure $callback,
        int $intervalMs = 100,
    ): int {
        $subscription = self::$nextSubscription++;
        self::$subscriptions[$subscription] = [
            'type' => $type,
            'callback' => $callback,
            'native' => null,
            'active' => true,
        ];
        NativeModules::call(
            'sensors',
            'watch',
            ['type' => $type->value, 'intervalMs' => max(16, min(60_000, $intervalMs))],
            static function ($result) use ($subscription): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    unset(self::$subscriptions[$subscription]);
                    throw new RuntimeException($result->payload);
                }
                $values = Wire::decodeMap($result->payload);
                $native = (int) ($values['subscription'] ?? 0);
                if (!isset(self::$subscriptions[$subscription])) {
                    NativeModules::call('sensors', 'stop', ['subscription' => $native], static fn (): null => null);
                    return;
                }
                self::$subscriptions[$subscription]['native'] = $native;
                self::next($subscription);
            },
        );
        return $subscription;
    }

    public static function unwatch(int $subscription): void
    {
        $watch = self::$subscriptions[$subscription] ?? null;
        unset(self::$subscriptions[$subscription]);
        if (is_array($watch) && is_int($watch['native'])) {
            NativeModules::call(
                'sensors',
                'stop',
                ['subscription' => $watch['native']],
                static fn (): null => null,
            );
        }
    }

    private static function next(int $subscription): void
    {
        $watch = self::$subscriptions[$subscription] ?? null;
        if (!is_array($watch) || !is_int($watch['native'])) {
            return;
        }
        NativeModules::call(
            'sensors',
            'next',
            ['subscription' => $watch['native']],
            static function ($result) use ($subscription): void {
                $watch = self::$subscriptions[$subscription] ?? null;
                if (!is_array($watch)) {
                    return;
                }
                if ($result->status === ModuleResultStatus::Failure) {
                    unset(self::$subscriptions[$subscription]);
                    return;
                }
                $values = Wire::decodeMap($result->payload);
                ($watch['callback'])(self::reading($watch['type'], $values));
                self::next($subscription);
            },
        );
    }

    private static function reading(SensorType $type, array $values): SensorReading
    {
        return new SensorReading(
            type: $type,
            x: self::number($values['x'] ?? null),
            y: self::number($values['y'] ?? null),
            z: self::number($values['z'] ?? null),
            timestamp: is_int($values['timestamp'] ?? null) ? $values['timestamp'] : 0,
        );
    }

    private static function number(mixed $value): float
    {
        return is_int($value) || is_float($value) ? (float) $value : 0.0;
    }
}
