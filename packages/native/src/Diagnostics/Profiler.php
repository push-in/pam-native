<?php

declare(strict_types=1);

namespace Pam\Native\Diagnostics;

use Closure;

final class Profiler
{
    private const LIMIT = 512;

    /** @var list<ProfileSpan> */
    private static array $spans = [];
    private static bool $enabled = true;

    private function __construct()
    {
    }

    /** @template T @param Closure(): T $callback @param array<string, string|int|float|bool> $metadata @return T */
    public static function measure(string $name, Closure $callback, array $metadata = []): mixed
    {
        if (!self::$enabled) {
            return $callback();
        }
        $started = hrtime(true);
        try {
            return $callback();
        } finally {
            self::$spans[] = new ProfileSpan(
                $name,
                (hrtime(true) - $started) / 1_000_000,
                microtime(true),
                $metadata,
            );
            // Trimmed in batches (amortized O(1)); spans() exposes the last LIMIT.
            if (count(self::$spans) >= 2 * self::LIMIT) {
                self::$spans = array_slice(self::$spans, -self::LIMIT);
            }
        }
    }

    /** @return list<ProfileSpan> */
    public static function spans(): array
    {
        return count(self::$spans) > self::LIMIT
            ? array_slice(self::$spans, -self::LIMIT)
            : self::$spans;
    }

    public static function enabled(bool $enabled): void
    {
        self::$enabled = $enabled;
    }

    public static function reset(): void
    {
        self::$spans = [];
    }
}
