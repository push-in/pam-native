<?php

declare(strict_types=1);

namespace Pam\Native\Storage;

use Closure;
use InvalidArgumentException;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;

/**
 * Small secrets (PIN hashes, tokens, keys) kept by the platform secure store:
 * Android Keystore-sealed preferences, iOS Keychain (this device only, while
 * unlocked). Values are limited to 64 KiB; use Storage for anything else.
 */
final class SecureStorage
{
    public const MAX_KEY_LENGTH = 256;
    public const MAX_VALUE_LENGTH = 65_536;

    private function __construct()
    {
    }

    /** @param Closure(?string): void $callback null when the key has no value (or could not be read) */
    public static function get(string $key, Closure $callback): int
    {
        return NativeModules::call('secure-storage', 'get', ['key' => self::key($key)], static function ($result) use ($callback): void {
            if ($result->status === ModuleResultStatus::Failure) {
                $callback(null);

                return;
            }
            $values = Wire::decodeMap($result->payload);
            $callback(($values['found'] ?? false) === true && is_string($values['value'] ?? null) ? $values['value'] : null);
        });
    }

    /** @param null|Closure(bool): void $callback Receives whether the value was stored. */
    public static function set(string $key, string $value, ?Closure $callback = null): int
    {
        if (mb_strlen($value) > self::MAX_VALUE_LENGTH) {
            throw new InvalidArgumentException('Secure values are limited to 65536 characters.');
        }

        return NativeModules::call('secure-storage', 'set', ['key' => self::key($key), 'value' => $value], static function ($result) use ($callback): void {
            $callback?->__invoke($result->status !== ModuleResultStatus::Failure);
        });
    }

    /** @param null|Closure(bool): void $callback */
    public static function delete(string $key, ?Closure $callback = null): int
    {
        return NativeModules::call('secure-storage', 'delete', ['key' => self::key($key)], static function ($result) use ($callback): void {
            $callback?->__invoke($result->status !== ModuleResultStatus::Failure);
        });
    }

    private static function key(string $key): string
    {
        if ($key === '' || mb_strlen($key) > self::MAX_KEY_LENGTH) {
            throw new InvalidArgumentException('Secure storage keys must contain between 1 and 256 characters.');
        }

        return $key;
    }
}
