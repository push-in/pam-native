<?php

declare(strict_types=1);

namespace Pam\Native\Cache;

use Closure;
use Pam\Native\Modules\NativeModuleException;
use Pam\Native\Storage\Storage;

/** Persists snapshots in the native key-value Storage (SharedPreferences / UserDefaults). */
final class StoragePersistence implements CachePersistence
{
    /** @param (Closure(NativeModuleException): void)|null $onError failures are ignored when null */
    public function __construct(private readonly ?Closure $onError = null)
    {
    }

    public function load(string $key, Closure $done): void
    {
        Storage::get($key, static function (?string $value) use ($done): void {
            $done($value === '' ? null : $value);
        }, $this->failure(static fn () => $done(null)));
    }

    public function save(string $key, string $value, ?Closure $done = null): void
    {
        Storage::set($key, $value, $done, $this->failure($done ?? static fn () => null));
    }

    private function failure(Closure $continue): Closure
    {
        $onError = $this->onError;

        return static function (NativeModuleException $error) use ($onError, $continue): void {
            $onError?->__invoke($error);
            $continue();
        };
    }
}
