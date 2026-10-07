<?php

declare(strict_types=1);

namespace Pam\Native\Diagnostics;

use Closure;

use function array_slice;
use function count;

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

    /** @internal Start of a span recorded with record(); 0 when disabled. */
    public static function start(): int
    {
        return self::$enabled ? hrtime(true) : 0;
    }

    /**
     * @internal Records a span started with start() (what measure() records
     * without a closure).
     *
     * @param array<string, string|int|float|bool> $metadata
     */
    public static function record(string $name, int $started, array $metadata = []): void
    {
        if (!self::$enabled || $started === 0) {
            return;
        }
        self::$spans[] = new ProfileSpan(
            $name,
            (hrtime(true) - $started) / 1_000_000,
            microtime(true),
            $metadata,
        );
        if (count(self::$spans) >= 2 * self::LIMIT) {
            self::$spans = array_slice(self::$spans, -self::LIMIT);
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
