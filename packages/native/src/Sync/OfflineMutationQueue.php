<?php

declare(strict_types=1);

namespace Pam\Native\Sync;

use Closure;
use InvalidArgumentException;
use JsonException;
use OverflowException;
use Pam\Native\App;
use Pam\Native\AppState;
use Pam\Native\Cache\CachePersistence;
use Pam\Native\DeviceStatus as DeviceStatusValue;
use Pam\Native\Events\Subscription;
use Pam\Native\NetworkType;
use Pam\Native\System\DeviceStatus;
use Pam\Native\System\TimerHandle;
use Pam\Native\System\Timers;
use Throwable;

use function array_slice;
use function count;
use function in_array;
use function is_array;
use function is_int;
use function is_string;
use function strlen;

/**
 * Durable queue of user intents made offline or while a request is in
 * flight (send message, like, follow). Each mutation has an idempotency
 * $key for the server and moves Queued → Sending → Applied, or to Retry
 * (with backoff), Conflict or Failed.
 *
 *     $queue = new OfflineMutationQueue(
 *         backoff: new BackoffPolicy(baseMs: 2_000, maxMs: 1_800_000, maxAttempts: 12),
 *         persistence: new StoragePersistence(),
 *         name: "sync.{$accountId}",
 *     );
 *     $queue->hydrate();
 *     $queue->drainOn(fn (OfflineMutationQueue $q) => $this->send($q->ready()),
 *         DrainTrigger::Enqueue, DrainTrigger::Connectivity, DrainTrigger::Foreground, DrainTrigger::RetryDue);
 *     $queue->enqueue("like:{$postId}:{$tap}", 'post.like', ['post' => $postId, 'liked' => true],
 *         dedupeKey: "like:{$postId}");
 *
 * A mutation enqueued with the dedupeKey of an unsent one (Queued or Retry)
 * replaces it, so ten like/unlike taps send only the last intent. With a
 * persistence, call hydrate() once at startup: from then on every change is
 * written (mutations queued before it are merged, never overwritten), and
 * mutations caught mid-send by process death are restored as Retry.
 */
final class OfflineMutationQueue
{
    private const MAX_MUTATIONS = 10_000;
    private const MAX_PAYLOAD_BYTES = 262_144;
    private const MAX_SNAPSHOT_BYTES = 4_194_304;
    private const CONNECTIVITY_POLL_MS = 3_000;

    /** @var array<int, Mutation> */
    private array $mutations = [];
    private int $nextId = 1;
    private BackoffPolicy $backoff;
    /** @var Closure(): int */
    private Closure $clock;
    /** @var array<int, array{drain: Closure, triggers: array<int, true>}> */
    private array $drainers = [];
    private int $nextDrainer = 1;
    private ?int $appStateSubscription = null;
    private ?int $connectivityWatch = null;
    private ?bool $online = null;
    private ?TimerHandle $retryTimer = null;
    private int $retryTimerAtMs = 0;
    private bool $backgrounded = false;
    private bool $draining = false;
    private bool $hydrated = false;
    private int $batchDepth = 0;
    private bool $batchDirty = false;
    /** @var list<array{0: (Closure(): void)|null, 1: (Closure(Throwable): void)|null, 2: int}> */
    private array $writeWaiters = [];

    /** @param (Closure(): int)|null $clock wall clock in milliseconds */
    public function __construct(
        ?BackoffPolicy $backoff = null,
        private readonly ?CachePersistence $persistence = null,
        public readonly string $name = 'sync.mutations',
        ?Closure $clock = null,
    ) {
        if (preg_match('/^[A-Za-z0-9][A-Za-z0-9_.:@-]{0,127}$/D', $name) !== 1) {
            throw new InvalidArgumentException('Offline mutation queue name must be a safe identifier.');
        }
        $this->backoff = $backoff ?? new BackoffPolicy(1_000, 1_024_000);
        $this->clock = $clock ?? static fn (): int => (int) floor(microtime(true) * 1_000);
    }

    /**
     * Queues an intent. An active mutation with the same $key is reused (its
     * payload is updated while unsent); an unsent one with the same
     * $dedupeKey is replaced in place, keeping its position.
     *
     * $stored runs once the queue holding this mutation has been written by
     * the persistence (at once without one; after hydrate() when called
     * before it); $failed receives the error when that write fails.
     *
     * @param array<string, mixed> $payload JSON values, at most 256 KiB encoded
     * $createdAtMs keeps the original time of an intent imported from
     * another store (defaults to now).
     *
     * @param (Closure(Mutation): void)|null $stored
     * @param (Closure(Throwable, Mutation): void)|null $failed
     */
    public function enqueue(
        string $key,
        string $operation,
        array $payload,
        ?string $dedupeKey = null,
        ?Closure $stored = null,
        ?Closure $failed = null,
        ?int $createdAtMs = null,
    ): Mutation {
        self::safeKey($key, 'idempotency key');
        self::safeName($operation, 'operation');
        if ($dedupeKey !== null) {
            self::safeKey($dedupeKey, 'dedupe key');
        }
        $encoded = json_encode($payload, JSON_THROW_ON_ERROR);
        if (strlen($encoded) > self::MAX_PAYLOAD_BYTES) {
            throw new InvalidArgumentException('Offline mutation payload exceeds 256 KiB.');
        }
        $target = null;
        foreach ($this->mutations as $mutation) {
            if ($mutation->key === $key && self::active($mutation)) {
                if (!self::unsent($mutation) || ($mutation->payload === $payload && $mutation->operation === $operation)) {
                    $this->acknowledge($mutation, $stored, $failed, false);

                    return $mutation;
                }
                $target = $mutation;
                break;
            }
        }
        if ($target === null && $dedupeKey !== null) {
            foreach ($this->mutations as $mutation) {
                if ($mutation->dedupeKey === $dedupeKey && self::unsent($mutation)) {
                    $target = $mutation;
                    break;
                }
            }
        }
        if ($target !== null) {
            $replacement = new Mutation(
                $target->id,
                $key,
                $operation,
                $payload,
                dedupeKey: $dedupeKey ?? $target->dedupeKey,
                createdAtMs: $createdAtMs ?? ($this->clock)(),
            );
            $this->mutations[$target->id] = $replacement;
            $this->acknowledge($replacement, $stored, $failed, true);
            $this->fire(DrainTrigger::Enqueue);

            return $replacement;
        }
        if (count($this->mutations) >= self::MAX_MUTATIONS) {
            throw new OverflowException('Offline mutation queue exceeded 10,000 entries.');
        }
        $mutation = new Mutation($this->nextId++, $key, $operation, $payload, dedupeKey: $dedupeKey, createdAtMs: $createdAtMs ?? ($this->clock)());
        $this->mutations[$mutation->id] = $mutation;
        $this->acknowledge($mutation, $stored, $failed, true);
        $this->fire(DrainTrigger::Enqueue);

        return $mutation;
    }

    /**
     * Applies several changes with a single persistence write, e.g. marking
     * a whole batch Sending or Applied.
     *
     * @template T
     * @param Closure(self): T $changes
     * @return T
     */
    public function batch(Closure $changes): mixed
    {
        ++$this->batchDepth;
        try {
            return $changes($this);
        } finally {
            --$this->batchDepth;
            if ($this->batchDepth === 0 && $this->batchDirty) {
                $this->batchDirty = false;
                $this->changed();
            }
        }
    }

    /** Drops every mutation (logout, account removal) and persists the empty queue. */
    public function clear(): void
    {
        $this->mutations = [];
        $this->changed();
    }

    /** @return list<Mutation> Queued and due Retry mutations, oldest first */
    public function ready(?int $nowMs = null, int $limit = 50): array
    {
        $nowMs ??= ($this->clock)();
        $ready = array_filter(
            $this->mutations,
            static fn (Mutation $mutation): bool => in_array(
                $mutation->status,
                [MutationStatus::Queued, MutationStatus::Retry],
                true,
            ) && $mutation->availableAtMs <= $nowMs,
        );

        return array_slice(array_values($ready), 0, min(500, max(1, $limit)));
    }

    public function get(int $id): ?Mutation
    {
        return $this->mutations[$id] ?? null;
    }

    /** @return list<Mutation> oldest first, optionally only one $operation */
    public function all(?string $operation = null): array
    {
        if ($operation === null) {
            return array_values($this->mutations);
        }

        return array_values(array_filter($this->mutations, static fn (Mutation $mutation): bool => $mutation->operation === $operation));
    }

    /** Mutations not yet Applied or Failed (including Conflict). */
    public function pendingCount(): int
    {
        return count(array_filter($this->mutations, self::active(...)));
    }

    /** Earliest availableAtMs among waiting retries, or null. */
    public function nextRetryAtMs(): ?int
    {
        $next = null;
        foreach ($this->mutations as $mutation) {
            if ($mutation->status === MutationStatus::Retry && ($next === null || $mutation->availableAtMs < $next)) {
                $next = $mutation->availableAtMs;
            }
        }

        return $next;
    }

    public function sending(int $id): Mutation
    {
        return $this->update($id, MutationStatus::Sending);
    }

    public function applied(int $id): Mutation
    {
        return $this->update($id, MutationStatus::Applied);
    }

    public function conflict(int $id, string $message): Mutation
    {
        return $this->update($id, MutationStatus::Conflict, $message);
    }

    /**
     * Schedules another attempt after the backoff delay, or marks the
     * mutation Failed once the policy's maxAttempts is exhausted.
     */
    public function retry(int $id, int $nowMs, string $message): Mutation
    {
        $mutation = $this->require($id);
        if ($this->backoff->exhausted($mutation->attempts)) {
            return $this->update($id, MutationStatus::Failed, $message);
        }
        $updated = $mutation->withStatus(
            MutationStatus::Retry,
            self::boundedError($message),
            $nowMs + $this->backoff->delayMs($mutation->attempts),
        );
        $this->mutations[$id] = $updated;
        $this->changed();

        return $updated;
    }

    public function failed(int $id, string $message): Mutation
    {
        return $this->update($id, MutationStatus::Failed, $message);
    }

    /** Removes Applied and Failed mutations; returns how many. */
    public function prune(): int
    {
        $before = count($this->mutations);
        $this->mutations = array_filter($this->mutations, self::active(...));
        $removed = $before - count($this->mutations);
        if ($removed > 0) {
            $this->changed();
        }

        return $removed;
    }

    /**
     * Calls $drain when any of $triggers fires and mutations are ready.
     * Connectivity polling and the retry timer run only while mutations are
     * pending, and stop in the background. Cancel the returned subscription
     * (or pass $owner) to stop.
     *
     * @param Closure(self): void $drain
     */
    public function drainOn(Closure $drain, DrainTrigger ...$triggers): Subscription
    {
        if ($triggers === []) {
            throw new InvalidArgumentException('drainOn() needs at least one DrainTrigger.');
        }
        $id = $this->nextDrainer++;
        $set = [];
        foreach ($triggers as $trigger) {
            $set[$trigger->value] = true;
        }
        $this->drainers[$id] = ['drain' => $drain, 'triggers' => $set];
        if ($this->appStateSubscription === null) {
            $this->backgrounded = App::state() === AppState::Background;
        }
        if (isset($set[DrainTrigger::Foreground->value]) || isset($set[DrainTrigger::Connectivity->value]) || isset($set[DrainTrigger::RetryDue->value])) {
            $this->appStateSubscription ??= App::onStateChange(function (AppState $state): void {
                $wasBackground = $this->backgrounded;
                $this->backgrounded = $state === AppState::Background;
                $this->reconcile();
                if ($wasBackground && !$this->backgrounded) {
                    $this->fire(DrainTrigger::Foreground);
                }
            });
        }
        $this->reconcile();
        $queue = \WeakReference::create($this);

        return Subscription::from(static function () use ($queue, $id): void {
            $queue->get()?->removeDrainer($id);
        });
    }

    /**
     * Runs every drain callback now when mutations are ready, whatever their
     * triggers. Returns false when nothing was ready.
     */
    public function drain(): bool
    {
        return $this->fire(null);
    }

    /** Reports connectivity from another source (realtime socket, HTTP success). */
    public function online(bool $online = true): void
    {
        $previous = $this->online;
        $this->online = $online;
        if ($online && $previous === false) {
            $this->fire(DrainTrigger::Connectivity);
        }
    }

    /**
     * Writes the queue (without Applied mutations). Called automatically
     * after every change once hydrate() has completed.
     *
     * @param (Closure(): void)|null $done
     */
    public function persist(?Closure $done = null): bool
    {
        if ($this->persistence === null) {
            return false;
        }
        $snapshot = $this->export(includeApplied: false);
        $limit = $this->persistence->maxValueBytes();
        $kept = null;
        if (strlen($snapshot) > $limit) {
            [$snapshot, $kept] = $this->boundedSnapshot($limit);
        }
        $waiters = $this->writeWaiters;
        $this->writeWaiters = [];
        $this->persistence->save(
            $this->storageKey(),
            $snapshot,
            static function () use ($waiters, $done, $kept): void {
                foreach ($waiters as [$stored, $failed, $id]) {
                    if ($kept !== null && !isset($kept[$id])) {
                        $failed?->__invoke(new OverflowException('The mutation did not fit the persisted snapshot; it stays in memory.'));

                        continue;
                    }
                    $stored?->__invoke();
                }
                $done?->__invoke();
            },
            static function (Throwable $error) use ($waiters, $done): void {
                foreach ($waiters as [, $failed]) {
                    $failed?->__invoke($error);
                }
                $done?->__invoke();
            },
        );

        return true;
    }

    /**
     * Loads the persisted queue, keeping mutations already queued in memory.
     * Mutations persisted while Sending come back as Retry. Calling it again
     * is safe (already known keys are skipped), e.g. after a hot reload
     * dropped a pending load.
     *
     * @param (Closure(int): void)|null $done receives the number restored
     */
    public function hydrate(?Closure $done = null): void
    {
        if ($this->persistence === null) {
            $done?->__invoke(0);

            return;
        }
        $this->persistence->load($this->storageKey(), function (?string $snapshot) use ($done): void {
            $restored = 0;
            if ($snapshot !== null && $snapshot !== '') {
                try {
                    $restored = $this->merge(self::restore($snapshot));
                } catch (InvalidArgumentException) {
                    $restored = 0;
                }
            }
            $this->hydrated = true;
            $this->changed();
            $done?->__invoke($restored);
        });
    }

    public function storageKey(): string
    {
        return 'pam.sync.'.$this->name;
    }

    public function export(bool $includeApplied = true): string
    {
        $mutations = $includeApplied
            ? $this->mutations
            : array_filter($this->mutations, static fn (Mutation $mutation): bool => $mutation->status !== MutationStatus::Applied);

        return json_encode([
            'version' => 1,
            'nextId' => $this->nextId,
            'mutations' => array_map(
                static fn (Mutation $mutation): array => [
                    'id' => $mutation->id,
                    'key' => $mutation->key,
                    'operation' => $mutation->operation,
                    'payload' => $mutation->payload,
                    'status' => $mutation->status->value,
                    'attempts' => $mutation->attempts,
                    'availableAtMs' => $mutation->availableAtMs,
                    'error' => $mutation->error,
                    'dedupeKey' => $mutation->dedupeKey,
                    'createdAtMs' => $mutation->createdAtMs,
                ],
                array_values($mutations),
            ),
        ], JSON_THROW_ON_ERROR);
    }

    /**
     * Snapshot that fits $limit: Failed mutations are left out first, then
     * the newest pending ones, so the oldest intents (sent first) survive. Mutations left
     * out stay in memory and are written again once the queue shrinks.
     */
    /** @return array{0: string, 1: array<int, true>} snapshot and the ids it holds */
    private function boundedSnapshot(int $limit): array
    {
        $kept = array_filter($this->mutations, static fn (Mutation $mutation): bool => self::active($mutation));
        $copy = new self($this->backoff, null, $this->name, $this->clock);
        $copy->nextId = $this->nextId;
        $copy->mutations = $kept;
        $snapshot = $copy->export(includeApplied: false);
        while ($copy->mutations !== [] && strlen($snapshot) > $limit) {
            $overflow = strlen($snapshot) - $limit;
            $drop = max(1, (int) ceil(count($copy->mutations) * $overflow / strlen($snapshot)));
            $copy->mutations = array_slice($copy->mutations, 0, max(0, count($copy->mutations) - $drop), true);
            $snapshot = $copy->export(includeApplied: false);
        }

        return [$snapshot, array_fill_keys(array_keys($copy->mutations), true)];
    }

    /** @param (Closure(): int)|null $clock */
    public static function restore(
        string $json,
        ?BackoffPolicy $backoff = null,
        ?CachePersistence $persistence = null,
        string $name = 'sync.mutations',
        ?Closure $clock = null,
    ): self {
        if ($json === '' || strlen($json) > self::MAX_SNAPSHOT_BYTES) {
            throw new InvalidArgumentException('Offline mutation snapshot is empty or exceeds 4 MiB.');
        }
        try {
            $data = json_decode($json, true, 64, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new InvalidArgumentException('Offline mutation snapshot is invalid.', previous: $error);
        }
        if (!is_array($data) || ($data['version'] ?? null) !== 1 || !is_array($data['mutations'] ?? null)) {
            throw new InvalidArgumentException('Offline mutation snapshot contract is invalid.');
        }
        $queue = new self($backoff, $persistence, $name, $clock);
        foreach ($data['mutations'] as $item) {
            if (!is_array($item) || !is_int($item['id'] ?? null) || !is_array($item['payload'] ?? null)) {
                throw new InvalidArgumentException('Offline mutation snapshot entry is invalid.');
            }
            $status = is_int($item['status'] ?? null) ? MutationStatus::tryFrom($item['status']) : null;
            if ($status === null || !is_string($item['key'] ?? null) || !is_string($item['operation'] ?? null)) {
                throw new InvalidArgumentException('Offline mutation snapshot entry has invalid typed fields.');
            }
            /** @var array<string, mixed> $payload */
            $payload = $item['payload'];
            $mutation = new Mutation(
                $item['id'],
                $item['key'],
                $item['operation'],
                $payload,
                $status,
                is_int($item['attempts'] ?? null) ? $item['attempts'] : 0,
                is_int($item['availableAtMs'] ?? null) ? $item['availableAtMs'] : 0,
                is_string($item['error'] ?? null) ? $item['error'] : null,
                is_string($item['dedupeKey'] ?? null) ? $item['dedupeKey'] : null,
                is_int($item['createdAtMs'] ?? null) ? $item['createdAtMs'] : 0,
            );
            $queue->mutations[$mutation->id] = $mutation;
        }
        $queue->nextId = max(
            is_int($data['nextId'] ?? null) ? $data['nextId'] : 1,
            $queue->mutations === [] ? 1 : max(array_keys($queue->mutations)) + 1,
        );

        return $queue;
    }

    private function merge(self $stored): int
    {
        $restored = 0;
        $keys = [];
        foreach ($this->mutations as $mutation) {
            $keys[$mutation->key] = true;
        }
        foreach ($stored->mutations as $mutation) {
            if (isset($keys[$mutation->key]) || count($this->mutations) >= self::MAX_MUTATIONS) {
                continue;
            }
            if ($mutation->status === MutationStatus::Sending) {
                $mutation = $mutation->withStatus(MutationStatus::Retry, $mutation->error, $mutation->availableAtMs);
            }
            $id = isset($this->mutations[$mutation->id]) ? $this->nextId++ : $mutation->id;
            $this->mutations[$id] = new Mutation(
                $id,
                $mutation->key,
                $mutation->operation,
                $mutation->payload,
                $mutation->status,
                $mutation->attempts,
                $mutation->availableAtMs,
                $mutation->error,
                $mutation->dedupeKey,
                $mutation->createdAtMs,
            );
            $this->nextId = max($this->nextId, $id + 1, $stored->nextId);
            ++$restored;
        }
        ksort($this->mutations);

        return $restored;
    }

    private function removeDrainer(int $id): void
    {
        unset($this->drainers[$id]);
        if ($this->drainers === [] && $this->appStateSubscription !== null) {
            App::offStateChange($this->appStateSubscription);
            $this->appStateSubscription = null;
        }
        $this->reconcile();
    }

    private function fire(?DrainTrigger $trigger): bool
    {
        if ($this->draining || $this->drainers === [] || $this->ready(null, 1) === []) {
            return false;
        }
        $this->draining = true;
        $ran = false;
        $failure = null;
        try {
            foreach ($this->drainers as $drainer) {
                if ($trigger !== null && !isset($drainer['triggers'][$trigger->value])) {
                    continue;
                }
                $ran = true;
                try {
                    ($drainer['drain'])($this);
                } catch (Throwable $error) {
                    $failure ??= $error;
                }
            }
        } finally {
            $this->draining = false;
        }
        if ($failure !== null) {
            throw $failure;
        }

        return $ran;
    }

    private function wants(DrainTrigger $trigger): bool
    {
        foreach ($this->drainers as $drainer) {
            if (isset($drainer['triggers'][$trigger->value])) {
                return true;
            }
        }

        return false;
    }

    /** Starts or stops the connectivity watch and the retry timer. */
    private function reconcile(): void
    {
        $pending = !$this->backgrounded && $this->pendingCount() > 0;
        if ($pending && $this->wants(DrainTrigger::Connectivity)) {
            if ($this->connectivityWatch === null) {
                $queue = \WeakReference::create($this);
                $this->connectivityWatch = DeviceStatus::watch(static function (DeviceStatusValue $status) use ($queue): void {
                    $queue->get()?->online($status->networkType !== NetworkType::None);
                }, self::CONNECTIVITY_POLL_MS);
            }
        } elseif ($this->connectivityWatch !== null) {
            DeviceStatus::unwatch($this->connectivityWatch);
            $this->connectivityWatch = null;
        }
        $next = $pending && $this->wants(DrainTrigger::RetryDue) ? $this->nextRetryAtMs() : null;
        if ($next === null) {
            $this->retryTimer?->cancel();
            $this->retryTimer = null;

            return;
        }
        if ($this->retryTimer !== null && $this->retryTimer->active() && $this->retryTimerAtMs === $next) {
            return;
        }
        $this->retryTimer?->cancel();
        $this->retryTimerAtMs = $next;
        $delay = (int) max(0, min(86_400_000, $next - ($this->clock)()));
        $this->retryTimer = Timers::timeout($delay, function (): void {
            $this->retryTimer = null;
            $this->fire(DrainTrigger::RetryDue);
            $this->reconcile();
        });
    }

    /**
     * @param (Closure(Mutation): void)|null $stored
     * @param (Closure(Throwable, Mutation): void)|null $failed
     */
    private function acknowledge(Mutation $mutation, ?Closure $stored, ?Closure $failed, bool $changed): void
    {
        if ($stored !== null || $failed !== null) {
            if ($this->persistence === null) {
                $stored?->__invoke($mutation);
            } else {
                $this->writeWaiters[] = [
                    $stored === null ? null : static fn () => $stored($mutation),
                    $failed === null ? null : static fn (Throwable $error) => $failed($error, $mutation),
                    $mutation->id,
                ];
                if (!$changed && $this->hydrated && $this->batchDepth === 0) {
                    $this->persist();
                }
            }
        }
        if ($changed) {
            $this->changed();
        }
    }

    private static function unsent(Mutation $mutation): bool
    {
        return in_array($mutation->status, [MutationStatus::Queued, MutationStatus::Retry], true);
    }

    private function changed(): void
    {
        if ($this->batchDepth > 0) {
            $this->batchDirty = true;

            return;
        }
        if ($this->hydrated) {
            $this->persist();
        }
        if ($this->drainers !== []) {
            $this->reconcile();
        }
    }

    private static function active(Mutation $mutation): bool
    {
        return !in_array($mutation->status, [MutationStatus::Applied, MutationStatus::Failed], true);
    }

    private function update(int $id, MutationStatus $status, ?string $error = null): Mutation
    {
        $updated = $this->require($id)->withStatus($status, $error === null ? null : self::boundedError($error));
        $this->mutations[$id] = $updated;
        $this->changed();

        return $updated;
    }

    private function require(int $id): Mutation
    {
        return $this->mutations[$id] ?? throw new InvalidArgumentException("Unknown offline mutation {$id}.");
    }

    private static function safeKey(string $value, string $label): void
    {
        if (preg_match('/^[\x21-\x7E]{1,255}$/D', $value) !== 1) {
            throw new InvalidArgumentException("Offline mutation {$label} must be 1-255 printable ASCII characters.");
        }
    }

    private static function safeName(string $value, string $label): void
    {
        if (preg_match('/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/', $value) !== 1) {
            throw new InvalidArgumentException("Offline mutation {$label} is invalid.");
        }
    }

    private static function boundedError(string $message): string
    {
        return strlen($message) <= 16_384 ? $message : substr($message, 0, 16_384);
    }
}
