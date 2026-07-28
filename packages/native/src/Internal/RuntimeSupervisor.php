<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\State;
use Throwable;

final class RuntimeSupervisor
{
    private const MAX_CONSECUTIVE_FAILURES = 3;
    private const MAX_RENDER_MS = 250.0;

    private static int $failures = 0;
    private static bool $safeMode = false;
    private static float $lastCheckpoint = 0.0;

    private function __construct()
    {
    }

    public static function committed(string $frame, float $durationMs): void
    {
        self::$failures = 0;
        self::$safeMode = false;
        $now = microtime(true);
        if ($now - self::$lastCheckpoint < 5.0) {
            return;
        }
        self::$lastCheckpoint = $now;
        try {
            State::set('runtime.checkpoint', [
                'version' => 1,
                'frameHash' => hash('xxh3', $frame),
                'committedAt' => $now,
                'renderMs' => $durationMs,
            ]);
        } catch (Throwable) {
            // Recovery metadata is best-effort and may never break a committed frame.
        }
    }

    public static function failed(Throwable $error): void
    {
        self::$failures++;
        self::$safeMode = self::$failures >= self::MAX_CONSECUTIVE_FAILURES;
        try {
            State::set('runtime.failure', [
                'version' => 1,
                'fingerprint' => hash('xxh3', $error::class."\0".$error->getMessage()),
                'failures' => self::$failures,
                'safeMode' => self::$safeMode,
                'failedAt' => microtime(true),
            ]);
        } catch (Throwable) {
            // Reporting a failure must not create another runtime failure.
        }
    }

    public static function slow(float $durationMs): bool
    {
        return $durationMs > self::MAX_RENDER_MS;
    }

    public static function safeMode(): bool
    {
        return self::$safeMode;
    }

    public static function reset(): void
    {
        self::$failures = 0;
        self::$safeMode = false;
        self::$lastCheckpoint = 0.0;
    }
}
