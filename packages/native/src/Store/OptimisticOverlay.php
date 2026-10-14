<?php

declare(strict_types=1);

namespace Pam\Native\Store;

use Closure;
use InvalidArgumentException;
use Pam\Native\Component;
use Pam\Native\Events\EventBus;
use Pam\Native\Events\Subscription;

use function array_key_exists;
use function count;

/**
 * Optimistic field overlay shared by every screen that shows the same
 * records (likes, saves, follows, read flags). A tap writes the expected
 * fields at once with begin(); the request then settle()s (the server answer
 * wins once nothing newer is in flight) or rollback()s (only the fields this
 * tap still owns go back). Lists render apply($key, $row).
 *
 *     $overlay = new OptimisticOverlay(maxEntries: 400);
 *     $overlay->begin("post:{$id}", ['liked' => true, 'likes' => $likes + 1]);
 *     Http::post(..., fn ($r) => $r->successful()
 *         ? $overlay->settle("post:{$id}", ['likes' => json_decode($r->body, true)['likes']])
 *         : $overlay->rollback("post:{$id}", ['liked' => true, 'likes' => $likes + 1], ['liked' => false, 'likes' => $likes]));
 *     $rows = $overlay->applyAll($rows, fn (array $row) => "post:{$row['id']}");
 *
 * Bounded LRU: beyond $maxEntries the least recently written settled entry
 * is dropped; entries with requests in flight are never evicted.
 */
final class OptimisticOverlay
{
    /** @var array<string, array{fields: array<string, mixed>, pending: int, revision: int}> least recent first */
    private array $entries = [];
    private int $revision = 0;
    private ?EventBus $bus = null;
    private ?Closure $resolver;

    /**
     * @param (Closure(array<string, mixed>, array<string, mixed>): array<string, mixed>)|null $resolver
     *        merges overlay fields into a row; default: overlay values replace row values
     */
    public function __construct(public readonly int $maxEntries = 400, ?Closure $resolver = null)
    {
        if ($maxEntries < 1) {
            throw new InvalidArgumentException('OptimisticOverlay needs room for at least one entry.');
        }
        $this->resolver = $resolver;
    }

    /**
     * Optimistic write; pending until settle() or rollback().
     *
     * @param array<string, mixed> $fields
     */
    public function begin(string $key, array $fields): void
    {
        if ($key === '' || $fields === []) {
            return;
        }
        $current = $this->entries[$key] ?? null;
        $this->write($key, [...($current['fields'] ?? []), ...$fields], ($current['pending'] ?? 0) + 1);
    }

    /**
     * The request finished. $confirmed fields (the server's answer) are
     * merged only when no newer request for $key is still in flight.
     *
     * @param array<string, mixed>|null $confirmed
     */
    public function settle(string $key, ?array $confirmed = null): void
    {
        $current = $this->entries[$key] ?? null;
        if ($current === null) {
            if ($confirmed !== null && $confirmed !== []) {
                $this->write($key, $confirmed, 0);
            }

            return;
        }
        $pending = max(0, $current['pending'] - 1);
        $fields = $confirmed !== null && $pending === 0 ? [...$current['fields'], ...$confirmed] : $current['fields'];
        if ($fields === $current['fields']) {
            // Nothing visible changed: refresh recency without notifying.
            unset($this->entries[$key]);
            $this->entries[$key] = ['fields' => $fields, 'pending' => $pending, 'revision' => ++$this->revision];

            return;
        }
        $this->write($key, $fields, $pending);
    }

    /**
     * The request failed. Each field in $applied that still holds the
     * applied value returns to its $restore value; fields changed by a later
     * begin() keep that newer value.
     *
     * @param array<string, mixed> $applied
     * @param array<string, mixed> $restore
     */
    public function rollback(string $key, array $applied, array $restore): void
    {
        $current = $this->entries[$key] ?? null;
        if ($current === null) {
            return;
        }
        $fields = $current['fields'];
        foreach ($applied as $field => $value) {
            if (array_key_exists($field, $fields) && $fields[$field] === $value && array_key_exists($field, $restore)) {
                $fields[$field] = $restore[$field];
            }
        }
        $this->write($key, $fields, max(0, $current['pending'] - 1));
    }

    /**
     * Confirmed write (a server push, a change made on another screen),
     * keeping any requests in flight.
     *
     * @param array<string, mixed> $fields
     */
    public function set(string $key, array $fields): void
    {
        if ($key === '' || $fields === []) {
            return;
        }
        $current = $this->entries[$key] ?? null;
        $merged = [...($current['fields'] ?? []), ...$fields];
        if ($current !== null && $merged === $current['fields']) {
            return;
        }
        $this->write($key, $merged, $current['pending'] ?? 0);
    }

    /** @return array<string, mixed>|null */
    public function fields(string $key): ?array
    {
        return $this->entries[$key]['fields'] ?? null;
    }

    public function pending(string $key): bool
    {
        return ($this->entries[$key]['pending'] ?? 0) > 0;
    }

    /**
     * $row as the user should see it (the same array when there is no overlay).
     *
     * @param array<string, mixed> $row
     * @return array<string, mixed>
     */
    public function apply(string $key, array $row): array
    {
        $fields = $this->entries[$key]['fields'] ?? null;
        if ($fields === null) {
            return $row;
        }
        if ($this->resolver !== null) {
            return ($this->resolver)($row, $fields);
        }
        foreach ($fields as $field => $value) {
            if (!array_key_exists($field, $row) || $row[$field] !== $value) {
                $row[$field] = $value;
            }
        }

        return $row;
    }

    /**
     * @param list<array<string, mixed>> $rows
     * @param Closure(array<string, mixed>): string $keyOf
     * @return list<array<string, mixed>>
     */
    public function applyAll(array $rows, Closure $keyOf): array
    {
        if ($this->entries === []) {
            return $rows;
        }
        foreach ($rows as $index => $row) {
            $rows[$index] = $this->apply($keyOf($row), $row);
        }

        return $rows;
    }

    /**
     * Current write revision. Capture it when requesting fresh server
     * copies and pass it to dropSettled() when they arrive.
     */
    public function revision(): int
    {
        return $this->revision;
    }

    /**
     * Fresh server copies replace settled overlays written before
     * $beforeRevision (all settled ones when null), without notifying.
     *
     * @param iterable<string> $keys
     */
    public function dropSettled(iterable $keys, ?int $beforeRevision = null): int
    {
        $dropped = 0;
        foreach ($keys as $key) {
            $entry = $this->entries[$key] ?? null;
            if ($entry !== null && $entry['pending'] <= 0 && ($beforeRevision === null || $entry['revision'] <= $beforeRevision)) {
                unset($this->entries[$key]);
                ++$dropped;
            }
        }

        return $dropped;
    }

    public function forget(string $key): void
    {
        unset($this->entries[$key]);
    }

    /** Drops everything, e.g. on logout or account switch. */
    public function clear(): void
    {
        $this->entries = [];
    }

    public function count(): int
    {
        return count($this->entries);
    }

    /**
     * Notified with ($key, $fields) after every visible change.
     *
     * @param Closure(string, array<string, mixed>): void $listener
     */
    public function on(Closure $listener, ?Component $owner = null): Subscription
    {
        $this->bus ??= new EventBus();

        return $this->bus->on('change', static function (array $change) use ($listener): void {
            $listener($change[0], $change[1]);
        }, $owner);
    }

    /** @param array<string, mixed> $fields */
    private function write(string $key, array $fields, int $pending): void
    {
        unset($this->entries[$key]);
        $this->entries[$key] = ['fields' => $fields, 'pending' => $pending, 'revision' => ++$this->revision];
        if (count($this->entries) > $this->maxEntries) {
            foreach ($this->entries as $candidate => $entry) {
                if (count($this->entries) <= $this->maxEntries) {
                    break;
                }
                if ($entry['pending'] <= 0 && (string) $candidate !== $key) {
                    unset($this->entries[$candidate]);
                }
            }
        }
        $this->bus?->emit('change', [$key, $fields]);
    }
}
