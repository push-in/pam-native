<?php

declare(strict_types=1);

namespace Pam\Native\Cache;

use Closure;

/**
 * Where BoundedStore, IdLedger and OfflineMutationQueue keep their snapshot.
 * StoragePersistence uses the native key-value Storage; CallbackPersistence
 * adapts any other backend (a Nitro/MMKV key-value store, SQLite, a file).
 */
interface CachePersistence
{
    /** @param Closure(?string): void $done receives null when nothing is stored */
    public function load(string $key, Closure $done): void;

    /**
     * @param (Closure(): void)|null $done
     * @param (Closure(\Throwable): void)|null $failed the write did not happen
     */
    public function save(string $key, string $value, ?Closure $done = null, ?Closure $failed = null): void;

    /** Largest snapshot this backend accepts; snapshots are trimmed to fit. */
    public function maxValueBytes(): int;
}
