<?php

declare(strict_types=1);

namespace Pam\Native\Storage;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;
use Pam\Native\Modules\NativeModuleException;

final class Storage
{
    private function __construct()
    {
    }

    /**
     * @param Closure(?string): void $callback
     * @param (Closure(NativeModuleException): void)|null $onError
     */
    public static function get(string $key, Closure $callback, ?Closure $onError = null): int
    {
        return Runtime::callNative(
            operation: NativeOperation::StorageGet,
            payload: Wire::map(['key' => $key]),
            callback: static function (ModuleResultStatus $status, string $payload) use ($callback, $onError): void {
                if ($status === ModuleResultStatus::Failure) {
                    $failure = new NativeModuleException('storage', 'get', $payload);
                    if ($onError === null) {
                        throw $failure;
                    }
                    $onError($failure);

                    return;
                }

                if ($payload === '') {
                    $callback(null);

                    return;
                }
                $values = Wire::decodeMap($payload);
                $callback(isset($values['value']) ? (string) $values['value'] : null);
            },
        );
    }

    /**
     * @param Closure(): void|null $callback
     * @param (Closure(NativeModuleException): void)|null $onError
     */
    public static function set(string $key, string $value, ?Closure $callback = null, ?Closure $onError = null): int
    {
        return Runtime::callNative(
            operation: NativeOperation::StorageSet,
            payload: Wire::map(['key' => $key, 'value' => $value]),
            callback: static function (ModuleResultStatus $status, string $payload) use ($callback, $onError): void {
                if ($status === ModuleResultStatus::Failure) {
                    $failure = new NativeModuleException('storage', 'set', $payload);
                    if ($onError === null) {
                        throw $failure;
                    }
                    $onError($failure);

                    return;
                }

                $callback?->__invoke();
            },
        );
    }
}
