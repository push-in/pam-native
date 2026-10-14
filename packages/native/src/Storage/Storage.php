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
    /** Largest value (UTF-8 bytes) the native key-value store accepts. */
    public const MAX_VALUE_BYTES = 262_144;

    /** Keys the native store accepts. */
    public const KEY_PATTERN = '/^[A-Za-z0-9_.-]{1,128}$/D';

    private function __construct()
    {
    }

    /**
     * Maps any identifier to a key the native store accepts: unsupported
     * characters become '-', and keys longer than 128 bytes keep a readable
     * prefix plus a hash.
     */
    public static function safeKey(string $key): string
    {
        $safe = (string) preg_replace('/[^A-Za-z0-9_.-]/', '-', $key);
        if ($safe === '') {
            return 'key-'.substr(hash('sha256', $key), 0, 16);
        }
        if (strlen($safe) > 128) {
            return substr($safe, 0, 95).'-'.substr(hash('sha256', $key), 0, 32);
        }

        return $safe;
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
