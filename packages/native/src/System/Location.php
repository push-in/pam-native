<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Wire;
use Pam\Native\LocationPosition;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class Location
{
    private static int $nextSubscription = 1;

    /** @var array<int, array{callback: Closure, failure: ?Closure, native: ?int}> */
    private static array $subscriptions = [];

    private function __construct()
    {
    }

    /**
     * @param Closure(LocationPosition): void $callback
     * @param Closure(string): void|null $failure
     */
    public static function current(
        Closure $callback,
        bool $highAccuracy = true,
        int $timeoutMs = 10_000,
        int $maximumAgeMs = 30_000,
        ?Closure $failure = null,
    ): int {
        return NativeModules::call(
            'location',
            'current',
            [
                'highAccuracy' => $highAccuracy,
                'maximumAgeMs' => max(0, min(300_000, $maximumAgeMs)),
                'timeoutMs' => max(1_000, min(60_000, $timeoutMs)),
            ],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $callback(self::position(Wire::decodeMap($result->payload)));
            },
        );
    }

    /**
     * Continuous position updates, like React Native's watchPosition: the
     * platform location service wakes the app only after the device moved
     * `$distanceFilterMeters` (0 = every update), at most every `$intervalMs`
     * on Android. Updates stop with clearWatch(); the app decides when to
     * watch (for example only in the foreground). `$failure` receives a
     * start error (permission missing, no enabled provider).
     *
     * @param Closure(LocationPosition): void $callback
     * @param Closure(string): void|null $failure
     */
    public static function watch(
        Closure $callback,
        bool $highAccuracy = true,
        float $distanceFilterMeters = 0.0,
        int $intervalMs = 5_000,
        ?Closure $failure = null,
    ): int {
        $subscription = self::$nextSubscription++;
        self::$subscriptions[$subscription] = [
            'callback' => $callback,
            'failure' => $failure,
            'native' => null,
        ];
        NativeModules::call(
            'location',
            'watch',
            [
                'distanceFilterMeters' => max(0.0, min(100_000.0, $distanceFilterMeters)),
                'highAccuracy' => $highAccuracy,
                'intervalMs' => max(0, min(3_600_000, $intervalMs)),
            ],
            static function ($result) use ($subscription): void {
                $watch = self::$subscriptions[$subscription] ?? null;
                if ($result->status === ModuleResultStatus::Failure) {
                    unset(self::$subscriptions[$subscription]);
                    if (is_array($watch) && $watch['failure'] !== null) {
                        ($watch['failure'])($result->payload);

                        return;
                    }
                    if (is_array($watch)) {
                        throw new RuntimeException($result->payload);
                    }

                    return;
                }
                $native = (int) (Wire::decodeMap($result->payload)['subscription'] ?? 0);
                if ($watch === null) {
                    // Cleared before the platform answered.
                    NativeModules::call('location', 'stop', ['subscription' => $native], static fn (): null => null);

                    return;
                }
                self::$subscriptions[$subscription]['native'] = $native;
                self::next($subscription);
            },
        );

        return $subscription;
    }

    public static function clearWatch(int $subscription): void
    {
        $watch = self::$subscriptions[$subscription] ?? null;
        unset(self::$subscriptions[$subscription]);
        if (is_array($watch) && is_int($watch['native'])) {
            NativeModules::call(
                'location',
                'stop',
                ['subscription' => $watch['native']],
                static fn (): null => null,
            );
        }
    }

    public static function watching(int $subscription): bool
    {
        return isset(self::$subscriptions[$subscription]);
    }

    private static function next(int $subscription): void
    {
        $watch = self::$subscriptions[$subscription] ?? null;
        if (!is_array($watch) || !is_int($watch['native'])) {
            return;
        }
        NativeModules::call(
            'location',
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
                ($watch['callback'])(self::position(Wire::decodeMap($result->payload)));
                self::next($subscription);
            },
        );
    }

    /** @param array<string, mixed> $values */
    private static function position(array $values): LocationPosition
    {
        return new LocationPosition(
            latitude: (float) ($values['latitude'] ?? 0.0),
            longitude: (float) ($values['longitude'] ?? 0.0),
            accuracy: (float) ($values['accuracy'] ?? 0.0),
            altitude: (float) ($values['altitude'] ?? 0.0),
            speed: (float) ($values['speed'] ?? 0.0),
            bearing: (float) ($values['bearing'] ?? 0.0),
            timestamp: (int) ($values['timestamp'] ?? 0),
        );
    }
}
