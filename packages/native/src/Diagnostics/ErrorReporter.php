<?php

declare(strict_types=1);

namespace Pam\Native\Diagnostics;

use Closure;
use Throwable;

/**
 * Crash-reporting hook for uncaught runtime errors. Listeners run in every
 * build mode before the host shows the developer overlay (debug) or the
 * friendly fallback (release), so Sentry/observability exporters see each
 * error exactly once. A listener that throws is ignored.
 */
final class ErrorReporter
{
    /** @var array<int, Closure(Throwable, RuntimeError): void> */
    private static array $listeners = [];

    private static int $nextListener = 1;

    private static bool $notifying = false;

    private function __construct()
    {
    }

    /** @param Closure(Throwable, RuntimeError): void $listener */
    public static function listen(Closure $listener): int
    {
        $id = self::$nextListener++;
        self::$listeners[$id] = $listener;

        return $id;
    }

    public static function unsubscribe(int $subscription): void
    {
        unset(self::$listeners[$subscription]);
    }

    public static function notify(Throwable $error, RuntimeError $report): void
    {
        if (self::$notifying) {
            return;
        }
        self::$notifying = true;
        try {
            foreach (self::$listeners as $listener) {
                try {
                    $listener($error, $report);
                } catch (Throwable) {
                    // A broken crash reporter must never mask the original error.
                }
            }
        } finally {
            self::$notifying = false;
        }
    }

    public static function reset(): void
    {
        self::$listeners = [];
        self::$nextListener = 1;
        self::$notifying = false;
    }
}
