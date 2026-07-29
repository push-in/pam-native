<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\AudioRecording;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class AudioRecorder
{
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
}
