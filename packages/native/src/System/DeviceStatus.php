<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\DeviceStatus as DeviceStatusValue;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\NetworkType;
use RuntimeException;

final class DeviceStatus
{
    private static int $nextSubscription = 1;

    /** @var array<int, array{callback: Closure, native: ?int}> */
    private static array $subscriptions = [];

    private function __construct()
    {
    }

    /** @param Closure(DeviceStatusValue): void $callback */
    public static function read(Closure $callback): int
    {
        return NativeModules::call('device', 'status', [], static function ($result) use ($callback): void {
            if ($result->status === ModuleResultStatus::Failure) {
                throw new RuntimeException($result->payload);
            }
            $callback(self::value(Wire::decodeMap($result->payload)));
        });
    }

    /** @param Closure(DeviceStatusValue): void $callback */
    public static function watch(Closure $callback, int $intervalMs = 1_000): int
    {
        $subscription = self::$nextSubscription++;
        self::$subscriptions[$subscription] = ['callback' => $callback, 'native' => null];
        NativeModules::call(
            'device',
            'watch',
            ['intervalMs' => max(250, min(60_000, $intervalMs))],
            static function ($result) use ($subscription): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    unset(self::$subscriptions[$subscription]);
                    throw new RuntimeException($result->payload);
                }
                $native = (int) (Wire::decodeMap($result->payload)['subscription'] ?? 0);
                if (!isset(self::$subscriptions[$subscription])) {
                    NativeModules::call('device', 'stop', ['subscription' => $native], static fn ($r): null => null);
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
            NativeModules::call('device', 'stop', ['subscription' => $watch['native']], static fn ($r): null => null);
        }
    }

    private static function next(int $subscription): void
    {
        $watch = self::$subscriptions[$subscription] ?? null;
        if (!is_array($watch) || !is_int($watch['native'])) {
            return;
        }
        NativeModules::call(
            'device',
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
                ($watch['callback'])(self::value(Wire::decodeMap($result->payload)));
                self::next($subscription);
            },
        );
    }

    private static function value(array $values): DeviceStatusValue
    {
        return new DeviceStatusValue(
            batteryLevel: (float) ($values['batteryLevel'] ?? -1.0),
            charging: (bool) ($values['charging'] ?? false),
            networkType: NetworkType::tryFrom((int) ($values['networkType'] ?? 1))
                ?? NetworkType::None,
            expensiveNetwork: (bool) ($values['expensiveNetwork'] ?? false),
            lowPowerMode: (bool) ($values['lowPowerMode'] ?? false),
        );
    }
}
