<?php

declare(strict_types=1);

namespace Pam\Native\Storage;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;
use RuntimeException;

final class Storage
{
    private function __construct()
    {
    }

    /** @param Closure(?string): void $callback */
    public static function get(string $key, Closure $callback): int
    {
        return Runtime::callNative(
            operation: NativeOperation::StorageGet,
            payload: Wire::map(['key' => $key]),
            callback: static function (ModuleResultStatus $status, string $payload) use ($callback): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $values = Wire::decodeMap($payload);
                $callback(isset($values['value']) ? (string) $values['value'] : null);
            },
        );
    }

    /** @param Closure(): void|null $callback */
    public static function set(string $key, string $value, ?Closure $callback = null): int
    {
        return Runtime::callNative(
            operation: NativeOperation::StorageSet,
            payload: Wire::map(['key' => $key, 'value' => $value]),
            callback: static function (ModuleResultStatus $status, string $payload) use ($callback): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $callback?->__invoke();
            },
        );
    }
}
