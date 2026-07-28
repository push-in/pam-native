<?php

declare(strict_types=1);

namespace Pam\Native\Store;

final class Stores
{
    private function __construct()
    {
    }

    /** @template T of Store @param class-string<T> $store @return T */
    public static function get(string $store): Store
    {
        return StoreManager::instance()->get($store);
    }

    public static function middleware(StoreMiddleware $middleware): void
    {
        StoreManager::instance()->middleware($middleware);
    }

    public static function persistence(StorePersistence $persistence): void
    {
        StoreManager::instance()->persistence($persistence);
    }

    /** @return list<StoreChange> */
    public static function history(): array
    {
        return StoreManager::instance()->history();
    }

    public static function timeTravel(int $changeId): bool
    {
        return StoreManager::instance()->timeTravel($changeId);
    }

    public static function resetRuntime(): void
    {
        StoreManager::resetInstance();
    }
}
