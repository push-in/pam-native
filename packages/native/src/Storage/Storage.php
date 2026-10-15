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
     * prefix plus a hash. Idempotent: a key that already matches KEY_PATTERN
     * is returned unchanged. get() and set() apply it to every key.
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
     * Reads `$key`. Every key passes through safeKey(), so any identifier
     * (`composer:recents:v1`, `account/42`) reaches the native store in its
     * accepted form; keys that already match KEY_PATTERN are unchanged.
     *
     * @param Closure(?string): void $callback
     * @param (Closure(NativeModuleException): void)|null $onError
     */
    public static function get(string $key, Closure $callback, ?Closure $onError = null): int
    {
        return Runtime::callNative(
            operation: NativeOperation::StorageGet,
            payload: Wire::map(['key' => self::safeKey($key)]),
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
     * Writes `$key` (mapped with safeKey(), like get()).
     *
     * @param Closure(): void|null $callback
     * @param (Closure(NativeModuleException): void)|null $onError
     */
    public static function set(string $key, string $value, ?Closure $callback = null, ?Closure $onError = null): int
    {
        return Runtime::callNative(
            operation: NativeOperation::StorageSet,
            payload: Wire::map(['key' => self::safeKey($key), 'value' => $value]),
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
