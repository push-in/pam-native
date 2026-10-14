<?php

declare(strict_types=1);

namespace Pam\Native\Cache;

use Closure;
use InvalidArgumentException;
use JsonException;

use function count;
use function is_array;
use function is_int;
use function is_string;
use function strlen;

/**
 * In-memory LRU cache with hard bounds: at most $maxItems entries, an
 * optional TTL and an optional byte budget (JSON size of the values, or
 * $sizeOf). The least recently used entries are evicted first. Entries can be
 * scoped to an account and persisted between launches.
 *
 *     $profiles = new BoundedStore('profiles', maxItems: 200, ttlMs: 600_000,
 *         maxBytes: 512 * 1024, scope: "account:{$accountId}",
 *         persistence: new StoragePersistence());
 *     $profiles->hydrate();                       // once, at startup
 *     $profiles->put($id, $profile);
 *     $profile = $profiles->get($id);             // null when missing or expired
 *     $profiles->persist();                       // e.g. on paused()
 *
 * Persisted values must be JSON values (arrays, strings, numbers, booleans,
 * null); values that cannot be encoded stay in memory only.
 */
final class BoundedStore
{
    private const MAX_SNAPSHOT_BYTES = 2_097_152;

    /** @var array<string, array{0: mixed, 1: ?int, 2: int}> value, expiresAtMs, bytes; LRU first */
    private array $entries = [];
    private int $bytes = 0;
    private bool $dirty = false;
    private ?string $scope;
    /** @var Closure(): int */
    private Closure $clock;
    /** @var (Closure(mixed): int)|null */
    private ?Closure $sizeOf;

    /**
     * @param (Closure(): int)|null $clock wall clock in milliseconds
     * @param (Closure(mixed): int)|null $sizeOf bytes accounted for a value
     */
    public function __construct(
        public readonly string $name,
        public readonly int $maxItems = 256,
        public readonly ?int $ttlMs = null,
        public readonly ?int $maxBytes = null,
        ?string $scope = null,
        private readonly ?CachePersistence $persistence = null,
        ?Closure $clock = null,
        ?Closure $sizeOf = null,
    ) {
        self::validName($name, 'name');
        if ($maxItems < 1 || $maxItems > 100_000) {
            throw new InvalidArgumentException('BoundedStore maxItems must be between 1 and 100,000.');
        }
        if (($ttlMs !== null && $ttlMs < 1) || ($maxBytes !== null && $maxBytes < 1)) {
            throw new InvalidArgumentException('BoundedStore ttlMs and maxBytes must be positive.');
        }
        if ($scope !== null) {
            self::validName($scope, 'scope');
        }
        $this->scope = $scope;
        $this->clock = $clock ?? static fn (): int => (int) floor(microtime(true) * 1_000);
        $this->sizeOf = $sizeOf;
    }

    public function get(string $key, mixed $default = null): mixed
    {
        $entry = $this->live($key);
        if ($entry === null) {
            return $default;
        }
        unset($this->entries[$key]);
        $this->entries[$key] = $entry;

        return $entry[0];
    }

    /** Reads without refreshing the entry's recency. */
    public function peek(string $key, mixed $default = null): mixed
    {
        return $this->live($key)[0] ?? $default;
    }

    public function has(string $key): bool
    {
        return $this->live($key) !== null;
    }

    /**
     * Stores $value as the most recent entry. Returns false when the value
     * alone exceeds the byte budget (any previous value is then removed).
     */
    public function put(string $key, mixed $value, ?int $ttlMs = null): bool
    {
        $ttlMs ??= $this->ttlMs;
        $bytes = $this->measure($key, $value);
        $this->remove($key);
        if ($this->maxBytes !== null && $bytes > $this->maxBytes) {
            return false;
        }
        $this->entries[$key] = [$value, $ttlMs === null ? null : ($this->clock)() + $ttlMs, $bytes];
        $this->bytes += $bytes;
        $this->dirty = true;
        $this->evict();

        return true;
    }

    /**
     * Updates a live entry in place, keeping its recency and expiry (for
     * FIFO-style stores and status flips). Returns false when the key is
     * missing or expired, or when the new value exceeds the byte budget.
     */
    public function replace(string $key, mixed $value): bool
    {
        $entry = $this->live($key);
        if ($entry === null) {
            return false;
        }
        $bytes = $this->measure($key, $value);
        if ($this->maxBytes !== null && $bytes > $this->maxBytes) {
            $this->remove($key);

            return false;
        }
        $this->entries[$key] = [$value, $entry[1], $bytes];
        $this->bytes += $bytes - $entry[2];
        $this->dirty = true;
        if ($this->maxBytes !== null && $this->bytes > $this->maxBytes) {
            $this->evict();
        }

        return isset($this->entries[$key]);
    }

    /**
     * Returns the cached value or computes, stores and returns it.
     *
     * @param Closure(): mixed $compute
     */
    public function remember(string $key, Closure $compute, ?int $ttlMs = null): mixed
    {
        $entry = $this->live($key);
        if ($entry !== null) {
            return $this->get($key);
        }
        $value = $compute();
        $this->put($key, $value, $ttlMs);

        return $value;
    }

    public function forget(string $key): bool
    {
        return $this->remove($key);
    }

    public function clear(): void
    {
        if ($this->entries !== []) {
            $this->dirty = true;
        }
        $this->entries = [];
        $this->bytes = 0;
    }

    /** Removes expired entries and returns how many were removed. */
    public function prune(): int
    {
        $now = ($this->clock)();
        $removed = 0;
        foreach ($this->entries as $key => $entry) {
            if ($entry[1] !== null && $entry[1] <= $now) {
                $this->remove((string) $key);
                ++$removed;
            }
        }

        return $removed;
    }

    /**
     * Keeps only the most recent fraction of entries; call it from
     * App::onMemoryPressure() (0.5 when hidden, 0.0 when critical).
     */
    public function trim(float $keep = 0.5): int
    {
        $target = (int) floor(count($this->entries) * max(0.0, min(1.0, $keep)));
        $removed = 0;
        while (count($this->entries) > $target) {
            $this->remove((string) array_key_first($this->entries));
            ++$removed;
        }

        return $removed;
    }

    /** @return list<string> keys from least to most recently used */
    public function keys(): array
    {
        $this->prune();

        return array_map('strval', array_keys($this->entries));
    }

    /** @return array<string, mixed> live values from least to most recently used */
    public function all(): array
    {
        $this->prune();
        $values = [];
        foreach ($this->entries as $key => $entry) {
            $values[(string) $key] = $entry[0];
        }

        return $values;
    }

    /** Number of live entries (expired ones are pruned first). */
    public function count(): int
    {
        if ($this->ttlMs !== null || $this->entries !== []) {
            $this->prune();
        }

        return count($this->entries);
    }

    /** Bytes accounted for the stored values. */
    public function bytes(): int
    {
        return $this->bytes;
    }

    public function scope(): ?string
    {
        return $this->scope;
    }

    /**
     * Switches the account scope. Entries of the previous scope are persisted
     * (when changed) and dropped from memory, so accounts never see each
     * other's data. Call hydrate() afterwards to load the new scope.
     */
    public function useScope(?string $scope): void
    {
        if ($scope === $this->scope) {
            return;
        }
        if ($scope !== null) {
            self::validName($scope, 'scope');
        }
        $this->persist();
        $this->entries = [];
        $this->bytes = 0;
        $this->dirty = false;
        $this->scope = $scope;
    }

    /** True when entries changed since the last persist() or hydrate(). */
    public function dirty(): bool
    {
        return $this->dirty;
    }

    /** Persistence key of the current scope's snapshot (StoragePersistence maps it with Storage::safeKey()). */
    public function storageKey(): string
    {
        return 'pam.cache.'.$this->name.($this->scope === null ? '' : '.'.$this->scope);
    }

    /**
     * Writes the snapshot when entries changed. Returns false when there is
     * no persistence or nothing changed.
     *
     * @param (Closure(): void)|null $done
     */
    public function persist(?Closure $done = null, bool $force = false): bool
    {
        if ($this->persistence === null || (!$this->dirty && !$force)) {
            return false;
        }
        $this->dirty = false;
        $this->persistence->save($this->storageKey(), $this->export($this->persistence->maxValueBytes()), $done);

        return true;
    }

    /**
     * Loads the current scope's snapshot. Entries already in memory are kept
     * and stay more recent than restored ones.
     *
     * @param (Closure(int): void)|null $done receives the number of restored entries
     */
    public function hydrate(?Closure $done = null): void
    {
        if ($this->persistence === null) {
            $done?->__invoke(0);

            return;
        }
        $scope = $this->scope;
        $this->persistence->load($this->storageKey(), function (?string $snapshot) use ($done, $scope): void {
            $restored = $snapshot === null || $scope !== $this->scope ? 0 : $this->import($snapshot);
            $done?->__invoke($restored);
        });
    }

    /**
     * JSON snapshot of the live, encodable entries (oldest first). When it
     * would exceed $maxBytes (2 MiB by default) the oldest entries are left out.
     */
    public function export(?int $maxBytes = null): string
    {
        $this->prune();
        $limit = min(self::MAX_SNAPSHOT_BYTES, max(64, $maxBytes ?? self::MAX_SNAPSHOT_BYTES)) - 32;
        $rows = [];
        $size = 0;
        foreach (array_reverse($this->entries, true) as $key => $entry) {
            if (!self::encodable($entry[0], 0)) {
                continue;
            }
            try {
                $row = json_encode([(string) $key, $entry[0], $entry[1]], JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES);
            } catch (JsonException) {
                continue;
            }
            $size += strlen($row) + 1;
            if ($size > $limit) {
                break;
            }
            $rows[] = $row;
        }

        return '{"v":1,"entries":['.implode(',', array_reverse($rows)).']}';
    }

    /** Restores a snapshot from export(); returns the number of entries restored. */
    public function import(string $snapshot): int
    {
        if ($snapshot === '' || strlen($snapshot) > self::MAX_SNAPSHOT_BYTES + 64) {
            return 0;
        }
        try {
            $data = json_decode($snapshot, true, 512, JSON_THROW_ON_ERROR);
        } catch (JsonException) {
            return 0;
        }
        if (!is_array($data) || ($data['v'] ?? null) !== 1 || !is_array($data['entries'] ?? null)) {
            return 0;
        }
        $now = ($this->clock)();
        $current = $this->entries;
        $this->entries = [];
        $this->bytes = 0;
        $restoredKeys = [];
        foreach ($data['entries'] as $row) {
            if (!is_array($row) || !is_string($row[0] ?? null) || isset($current[$row[0]])) {
                continue;
            }
            $expires = $row[2] ?? null;
            if ($expires !== null && (!is_int($expires) || $expires <= $now)) {
                continue;
            }
            $bytes = $this->measure($row[0], $row[1] ?? null);
            if ($this->maxBytes !== null && $bytes > $this->maxBytes) {
                continue;
            }
            if (isset($this->entries[$row[0]])) {
                $this->bytes -= $this->entries[$row[0]][2];
                unset($this->entries[$row[0]]);
            }
            $this->entries[$row[0]] = [$row[1] ?? null, $expires, $bytes];
            $this->bytes += $bytes;
            $restoredKeys[] = $row[0];
        }
        foreach ($current as $key => $entry) {
            $this->entries[$key] = $entry;
            $this->bytes += $entry[2];
        }
        $this->evict();
        $restored = 0;
        foreach ($restoredKeys as $key) {
            if (isset($this->entries[$key])) {
                ++$restored;
            }
        }
        $this->dirty = $current !== [];

        return $restored;
    }

    /** @return array{0: mixed, 1: ?int, 2: int}|null */
    private function live(string $key): ?array
    {
        $entry = $this->entries[$key] ?? null;
        if ($entry === null) {
            return null;
        }
        if ($entry[1] !== null && $entry[1] <= ($this->clock)()) {
            $this->remove($key);

            return null;
        }

        return $entry;
    }

    private function remove(string $key): bool
    {
        $entry = $this->entries[$key] ?? null;
        if ($entry === null) {
            return false;
        }
        unset($this->entries[$key]);
        $this->bytes -= $entry[2];
        $this->dirty = true;

        return true;
    }

    private function evict(): void
    {
        while (
            count($this->entries) > $this->maxItems
            || ($this->maxBytes !== null && $this->bytes > $this->maxBytes)
        ) {
            $this->remove((string) array_key_first($this->entries));
        }
    }

    private function measure(string $key, mixed $value): int
    {
        if ($this->sizeOf !== null) {
            return max(0, (int) ($this->sizeOf)($value));
        }
        if ($this->maxBytes === null) {
            return 0;
        }
        try {
            return strlen($key) + strlen(json_encode($value, JSON_THROW_ON_ERROR | JSON_UNESCAPED_UNICODE | JSON_UNESCAPED_SLASHES));
        } catch (JsonException) {
            return strlen($key) + 64;
        }
    }

    private static function encodable(mixed $value, int $depth): bool
    {
        if ($value === null || is_scalar($value)) {
            return true;
        }
        if ($value instanceof \JsonSerializable || $value instanceof \stdClass) {
            return true;
        }
        if (!is_array($value) || $depth > 32) {
            return false;
        }
        foreach ($value as $item) {
            if (!self::encodable($item, $depth + 1)) {
                return false;
            }
        }

        return true;
    }

    private static function validName(string $value, string $label): void
    {
        if (preg_match('/^[A-Za-z0-9][A-Za-z0-9_.:@-]{0,127}$/D', $value) !== 1) {
            throw new InvalidArgumentException("BoundedStore {$label} must be a safe identifier.");
        }
    }
}
