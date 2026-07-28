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
            if (count(self::$spans) > self::LIMIT) {
                array_shift(self::$spans);
            }
        }
    }

    /** @return list<ProfileSpan> */
    public static function spans(): array
    {
        return self::$spans;
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
