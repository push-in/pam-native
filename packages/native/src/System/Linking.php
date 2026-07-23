<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;
use RuntimeException;

final class Linking
{
    private function __construct()
    {
    }

    public static function open(string $url, ?Closure $opened = null): int
    {
        return Runtime::callNative(
            NativeOperation::OpenUrl,
            Wire::map(['url' => $url]),
            static function (ModuleResultStatus $status, string $payload) use ($opened): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $opened?->__invoke();
            },
        );
    }

    /** @param Closure(bool): void $callback */
    public static function canOpen(string $url, Closure $callback): int
    {
        return Runtime::callNative(
            NativeOperation::CanOpenUrl,
            Wire::map(['url' => $url]),
            static function (ModuleResultStatus $status, string $payload) use ($callback): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $values = Wire::decodeMap($payload);
                $callback((bool) ($values['supported'] ?? false));
            },
        );
    }
}
