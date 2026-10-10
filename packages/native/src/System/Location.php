<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Wire;
use Pam\Native\LocationError;
use Pam\Native\LocationPosition;
use Pam\Native\LocationServicesResult;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

/**
 * Device position (React Native geolocation). Android uses the Play Services
 * fused provider when Google Play Services is available and the platform
 * LocationManager otherwise; iOS uses Core Location.
 *
 * Failures reach `$failure` as "<code>: <detail>" strings; LocationError::fromFailure()
 * gives the typed reason (Permission, Disabled, Unavailable, Timeout).
 */
final class Location
{
    private static int $nextSubscription = 1;

    /** @var array<int, array{callback: Closure, failure: ?Closure, native: ?int}> */
    private static array $subscriptions = [];

    private function __construct()
    {
    }

    /**
     * One fix. A cached position at most `$maximumAgeMs` old is returned
     * without waiting for a new one.
     *
     * @param Closure(LocationPosition): void $callback
     * @param Closure(string): void|null $failure "<code>: <detail>", see LocationError::fromFailure()
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
     * start error (LocationError::Permission, LocationError::Disabled).
     *
     * @param Closure(LocationPosition): void $callback
     * @param Closure(string): void|null $failure "<code>: <detail>", see LocationError::fromFailure()
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

    /**
     * The last position the platform knows (fused `lastLocation`, then every
     * LocationManager provider on Android; `CLLocationManager.location` on
     * iOS) without turning on GPS. Fails with LocationError::Unavailable when
     * there is none, LocationError::Permission without permission.
     *
     * @param Closure(LocationPosition): void $callback
     * @param Closure(string): void|null $failure "<code>: <detail>", see LocationError::fromFailure()
     */
    public static function lastKnown(Closure $callback, ?Closure $failure = null): int
    {
        return NativeModules::call(
            'location',
            'lastKnown',
            [],
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
     * Whether the system location switch is on (not the app permission).
     * Android: LocationManagerCompat.isLocationEnabled; iOS:
     * CLLocationManager.locationServicesEnabled().
     *
     * @param Closure(bool): void $callback
     */
    public static function servicesEnabled(Closure $callback): int
    {
        return NativeModules::call(
            'location',
            'servicesEnabled',
            [],
            static function ($result) use ($callback): void {
                $callback(
                    $result->status === ModuleResultStatus::Success
                        && (Wire::decodeMap($result->payload)['enabled'] ?? false) === true,
                );
            },
        );
    }

    /**
     * Asks to turn the system location on. Android shows Google Play
     * Services' "turn on location" dialog (SettingsClient); without Play
     * Services or an activity, and on iOS while the switch is off, it
     * resolves Unavailable: offer openSettings() then.
     *
     * @param Closure(LocationServicesResult): void $callback
     */
    public static function requestServices(Closure $callback): int
    {
        return NativeModules::call(
            'location',
            'requestServices',
            [],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    $callback(LocationServicesResult::Unavailable);

                    return;
                }
                $value = Wire::decodeMap($result->payload)['result'] ?? null;
                $callback(
                    is_int($value)
                        ? LocationServicesResult::tryFrom($value) ?? LocationServicesResult::Unavailable
                        : LocationServicesResult::Unavailable,
                );
            },
        );
    }

    /**
     * Opens the system location settings (Android
     * ACTION_LOCATION_SOURCE_SETTINGS; iOS the app's Settings page, the
     * only one apps may open). `$completed` receives whether it opened.
     *
     * @param Closure(bool): void|null $completed
     */
    public static function openSettings(?Closure $completed = null): int
    {
        return NativeModules::call(
            'location',
            'openSettings',
            [],
            static function ($result) use ($completed): void {
                if ($completed === null) {
                    return;
                }
                $completed(
                    $result->status === ModuleResultStatus::Success
                        && (Wire::decodeMap($result->payload)['opened'] ?? false) === true,
                );
            },
        );
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
