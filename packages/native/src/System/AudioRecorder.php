<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\AudioRecording;
use Pam\Native\AudioRecordingProgress;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class AudioRecorder
{
    private static int $nextSubscription = 1;

    /** @var array<int, array{callback: Closure, native: ?int}> */
    private static array $subscriptions = [];

    private function __construct()
    {
    }

    /** @param Closure(): void $callback */
    public static function start(Closure $callback): int
    {
        return NativeModules::call(
            'audio-recorder',
            'start',
            [],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $callback();
            },
        );
    }

    /** @param Closure(AudioRecording): void $callback */
    public static function stop(Closure $callback): int
    {
        return NativeModules::call(
            'audio-recorder',
            'stop',
            [],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $values = Wire::decodeMap($result->payload);
                $callback(new AudioRecording(
                    uri: (string) ($values['uri'] ?? ''),
                    fileName: (string) ($values['fileName'] ?? 'voice.m4a'),
                    mimeType: (string) ($values['mimeType'] ?? 'audio/mp4'),
                    durationMs: max(0, (int) ($values['durationMs'] ?? 0)),
                    size: max(0, (int) ($values['size'] ?? 0)),
                    relativePath: (string) ($values['relativePath'] ?? ''),
                ));
            },
        );
    }

    /** @param Closure(): void $callback */
    public static function cancel(Closure $callback): int
    {
        return NativeModules::call(
            'audio-recorder',
            'cancel',
            [],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $callback();
            },
        );
    }

    /** @param Closure(): void $callback */
    public static function discard(string $uri, Closure $callback): int
    {
        return NativeModules::call(
            'audio-recorder',
            'discard',
            ['uri' => $uri],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $callback();
            },
        );
    }

    /** @param Closure(AudioRecordingProgress): void $callback */
    public static function watch(Closure $callback, int $intervalMs = 100): int
    {
        $subscription = self::$nextSubscription++;
        self::$subscriptions[$subscription] = ['callback' => $callback, 'native' => null];
        NativeModules::call(
            'audio-recorder',
            'watch',
            ['intervalMs' => max(50, min(1_000, $intervalMs))],
            static function ($result) use ($subscription): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    unset(self::$subscriptions[$subscription]);
                    throw new RuntimeException($result->payload);
                }
                $native = (int) (Wire::decodeMap($result->payload)['subscription'] ?? 0);
                if (!isset(self::$subscriptions[$subscription])) {
                    NativeModules::call(
                        'audio-recorder',
                        'unwatch',
                        ['subscription' => $native],
                        static fn ($result): null => null,
                    );

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
                'audio-recorder',
                'unwatch',
                ['subscription' => $watch['native']],
                static fn ($result): null => null,
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
            'audio-recorder',
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
                ($watch['callback'])(new AudioRecordingProgress(
                    durationMs: max(0, (int) ($values['durationMs'] ?? 0)),
                    amplitude: max(0.0, min(1.0, (float) ($values['amplitude'] ?? 0.0))),
                ));
                self::next($subscription);
            },
        );
    }
}
