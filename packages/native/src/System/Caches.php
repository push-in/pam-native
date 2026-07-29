<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\CacheUsage;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class Caches
{
    private function __construct()
    {
    }

    /** @param Closure(CacheUsage): void $callback */
    public static function usage(Closure $callback): int
    {
        return self::call('usage', [], $callback);
    }

    /** @param Closure(CacheUsage): void $callback */
    public static function clear(
        Closure $callback,
        bool $preserveOffline = true,
    ): int {
        return self::call(
            'clear',
            ['preserveOffline' => $preserveOffline],
            $callback,
        );
    }

    /**
     * @param array<string, bool> $values
     * @param Closure(CacheUsage): void $callback
     */
    private static function call(
        string $method,
        array $values,
        Closure $callback,
    ): int {
        return NativeModules::call(
            'cache',
            $method,
            $values,
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $values = $result->values();
                $callback(new CacheUsage(
                    fileCount: max(0, (int) ($values['fileCount'] ?? 0)),
                    imageBytes: max(0, (int) ($values['imageBytes'] ?? 0)),
                    mediaBytes: max(0, (int) ($values['mediaBytes'] ?? 0)),
                    temporaryBytes: max(0, (int) ($values['temporaryBytes'] ?? 0)),
                    totalBytes: max(0, (int) ($values['totalBytes'] ?? 0)),
                    freedBytes: max(0, (int) ($values['freedBytes'] ?? 0)),
                ));
            },
        );
    }
}
