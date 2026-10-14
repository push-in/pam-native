<?php

declare(strict_types=1);

namespace Pam\Native\Cache;

use Closure;
use Pam\Native\Modules\NativeModuleException;
use Pam\Native\Storage\Storage;
use Pam\Native\Storage\StorageBudget;

/**
 * Persists snapshots in the native key-value Storage (SharedPreferences /
 * UserDefaults): keys pass through Storage::safeKey() and snapshots are kept
 * under StorageBudget::MAX_BYTES.
 */
final class StoragePersistence implements CachePersistence
{
    /** @param (Closure(NativeModuleException): void)|null $onError failures are ignored when null */
    public function __construct(private readonly ?Closure $onError = null)
    {
    }

    public function maxValueBytes(): int
    {
        return StorageBudget::MAX_BYTES;
    }

    /** Keys are mapped with Storage::safeKey(). */
    public function load(string $key, Closure $done): void
    {
        Storage::get(Storage::safeKey($key), static function (?string $value) use ($done): void {
            $done($value === '' ? null : $value);
        }, $this->failure(static fn () => $done(null)));
    }

    public function save(string $key, string $value, ?Closure $done = null, ?Closure $failed = null): void
    {
        $onError = $this->onError;
        Storage::set(Storage::safeKey($key), $value, $done, static function (NativeModuleException $error) use ($onError, $failed, $done): void {
            $onError?->__invoke($error);
            if ($failed !== null) {
                $failed($error);

                return;
            }
            $done?->__invoke();
        });
    }

    /** @param Closure(): void $continue */
    private function failure(Closure $continue): Closure
    {
        $onError = $this->onError;

        return static function (NativeModuleException $error) use ($onError, $continue): void {
            $onError?->__invoke($error);
            $continue();
        };
    }
}
