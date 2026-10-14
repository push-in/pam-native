<?php

declare(strict_types=1);

namespace Pam\Native\Cache;

use Closure;

/**
 * Bounded set of ids with an optional TTL, e.g. posts deleted or hidden
 * locally that must stay filtered out of stale feed pages, messages already
 * acknowledged, notifications already shown. The oldest ids are dropped
 * first once $maxIds is reached.
 *
 *     $deleted = new IdLedger('feed.deleted', maxIds: 2_000, ttlMs: 86_400_000,
 *         scope: "account:{$accountId}", persistence: new StoragePersistence());
 *     $deleted->add($postId);
 *     $visible = $deleted->filter($posts, fn (Post $post) => $post->id);
 */
final class IdLedger
{
    private BoundedStore $ids;

    /** @param (Closure(): int)|null $clock wall clock in milliseconds */
    public function __construct(
        string $name,
        int $maxIds = 1_024,
        ?int $ttlMs = null,
        ?string $scope = null,
        ?CachePersistence $persistence = null,
        ?Closure $clock = null,
    ) {
        $this->ids = new BoundedStore($name, $maxIds, $ttlMs, null, $scope, $persistence, $clock);
    }

    public function add(string|int $id, ?int $ttlMs = null): void
    {
        $this->ids->put((string) $id, 1, $ttlMs);
    }

    public function has(string|int $id): bool
    {
        return $this->ids->has((string) $id);
    }

    public function remove(string|int $id): bool
    {
        return $this->ids->forget((string) $id);
    }

    /**
     * Returns the items whose id is not in the ledger, in their original order.
     *
     * @template T
     * @param iterable<T> $items
     * @param Closure(T): (string|int) $id
     * @return list<T>
     */
    public function filter(iterable $items, Closure $id): array
    {
        $kept = [];
        foreach ($items as $item) {
            if (!$this->ids->has((string) $id($item))) {
                $kept[] = $item;
            }
        }

        return $kept;
    }

    /** @return list<string> oldest first */
    public function ids(): array
    {
        return $this->ids->keys();
    }

    public function count(): int
    {
        return $this->ids->count();
    }

    public function clear(): void
    {
        $this->ids->clear();
    }

    public function useScope(?string $scope): void
    {
        $this->ids->useScope($scope);
    }

    /** @param (Closure(): void)|null $done */
    public function persist(?Closure $done = null): bool
    {
        return $this->ids->persist($done);
    }

    /** @param (Closure(int): void)|null $done */
    public function hydrate(?Closure $done = null): void
    {
        $this->ids->hydrate($done);
    }

    public function export(): string
    {
        return $this->ids->export();
    }

    public function import(string $snapshot): int
    {
        return $this->ids->import($snapshot);
    }
}
