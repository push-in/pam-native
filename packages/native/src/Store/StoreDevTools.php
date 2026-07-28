<?php

declare(strict_types=1);

namespace Pam\Native\Store;

final class StoreDevTools
{
    private function __construct()
    {
    }

    /** @return list<array<string, mixed>> */
    public static function timeline(): array
    {
        return array_map(
            static fn (StoreChange $change): array => [
                'id' => $change->id,
                'store' => $change->store,
                'name' => $change->name,
                'kind' => $change->kind->value,
                'diff' => $change->diff,
                'timestamp' => $change->timestamp,
            ],
            Stores::history(),
        );
    }

    public static function timeTravel(int $changeId): bool
    {
        return Stores::timeTravel($changeId);
    }

    public static function reset(Store $store): void
    {
        $store->reset();
    }

    public static function exportJson(): string
    {
        return json_encode(
            ['version' => 1, 'timeline' => self::timeline()],
            JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES,
        );
    }
}
