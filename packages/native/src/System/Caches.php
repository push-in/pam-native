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

    /**
     * @param Closure(CacheUsage): void $callback
     * @param null|Closure(string): void $failure Receives the native error instead of an exception.
     */
    public static function usage(Closure $callback, ?Closure $failure = null): int
    {
        return self::call('usage', [], $callback, $failure);
    }

    /**
     * @param Closure(CacheUsage): void $callback
     * @param null|Closure(string): void $failure Receives the native error instead of an exception.
     */
    public static function clear(
        Closure $callback,
        bool $preserveOffline = true,
        ?Closure $failure = null,
    ): int {
        return self::call(
            'clear',
            ['preserveOffline' => $preserveOffline],
            $callback,
            $failure,
        );
    }

    /**
     * @param array<string, bool> $values
     * @param Closure(CacheUsage): void $callback
     * @param null|Closure(string): void $failure
     */
    private static function call(
        string $method,
        array $values,
        Closure $callback,
        ?Closure $failure,
    ): int {
        return NativeModules::call(
            'cache',
            $method,
            $values,
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
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
