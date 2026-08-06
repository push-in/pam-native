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
                $values = Wire::decodeMap($result->payload);
                $callback(new LocationPosition(
                    latitude: (float) ($values['latitude'] ?? 0.0),
                    longitude: (float) ($values['longitude'] ?? 0.0),
                    accuracy: (float) ($values['accuracy'] ?? 0.0),
                    altitude: (float) ($values['altitude'] ?? 0.0),
                    speed: (float) ($values['speed'] ?? 0.0),
                    bearing: (float) ($values['bearing'] ?? 0.0),
                    timestamp: (int) ($values['timestamp'] ?? 0),
                ));
            },
        );
    }
}
