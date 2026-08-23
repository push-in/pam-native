<?php

declare(strict_types=1);

namespace Pam\Native\LocalFirst;

final class LocalStore
{
    /** @var array<string, LocalRecord> */
    private array $records = [];
    /** @var list<array<string, mixed>> */
    private array $outbox = [];

    /** @param array<string, mixed> $attributes */
    public function put(string $collection, string $id, array $attributes, ?int $updatedAtMs = null): LocalRecord
    {
        $key = "{$collection}:{$id}";
        $previous = $this->records[$key] ?? null;
        $record = new LocalRecord(
            $collection,
            $id,
            $attributes,
            ($previous === null ? 0 : $previous->version) + 1,
            $updatedAtMs ?? (int) floor(microtime(true) * 1_000),
        );
        $this->records[$key] = $record;
        $this->outbox[] = self::entry($record);
        return $record;
    }

    public function get(string $collection, string $id): ?LocalRecord
    {
        $record = $this->records["{$collection}:{$id}"] ?? null;
        return $record?->deleted === true ? null : $record;
    }

    /** @return list<array<string, mixed>> */
    public function drainOutbox(int $limit = 100): array
    {
        if ($limit < 1 || $limit > 1_000) {
            throw new \InvalidArgumentException('Sync batch limit must be between 1 and 1,000.');
        }
        return array_splice($this->outbox, 0, $limit);
    }

    /** @param list<LocalRecord> $remote @param null|\Closure(LocalRecord, LocalRecord): LocalRecord $merge */
    public function merge(array $remote, ConflictPolicy $policy, ?\Closure $merge = null): void
    {
        foreach ($remote as $incoming) {
            $key = "{$incoming->collection}:{$incoming->id}";
            $local = $this->records[$key] ?? null;
            if ($local === null) {
                $this->records[$key] = $incoming;
                continue;
            }
            $this->records[$key] = match ($policy) {
                ConflictPolicy::ClientWins => $local,
                ConflictPolicy::ServerWins => $incoming,
                ConflictPolicy::LatestWriteWins => $incoming->updatedAtMs >= $local->updatedAtMs ? $incoming : $local,
                ConflictPolicy::Merge => $merge !== null
                    ? $merge($local, $incoming)
                    : throw new \LogicException('Merge conflict policy requires a resolver.'),
            };
        }
    }

    /** @return array<string, mixed> */
    private static function entry(LocalRecord $record): array
    {
        return [
            'collection' => $record->collection,
            'id' => $record->id,
            'attributes' => $record->attributes,
            'version' => $record->version,
            'updatedAtMs' => $record->updatedAtMs,
            'deleted' => $record->deleted,
        ];
    }
}
