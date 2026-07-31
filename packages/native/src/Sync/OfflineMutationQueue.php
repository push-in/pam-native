<?php

declare(strict_types=1);

namespace Pam\Native\Sync;

use InvalidArgumentException;
use JsonException;
use OverflowException;

final class OfflineMutationQueue
{
    private const MAX_MUTATIONS = 10_000;
    private const MAX_PAYLOAD_BYTES = 262_144;
    /** @var array<int, Mutation> */
    private array $mutations = [];
    private int $nextId = 1;

    /** @param array<string, string|int|float|bool|null> $payload */
    public function enqueue(string $key, string $operation, array $payload): Mutation
    {
        if (count($this->mutations) >= self::MAX_MUTATIONS) {
            throw new OverflowException('Offline mutation queue exceeded 10,000 entries.');
        }
        self::safeName($key, 'idempotency key');
        self::safeName($operation, 'operation');
        $encoded = json_encode($payload, JSON_THROW_ON_ERROR);
        if (strlen($encoded) > self::MAX_PAYLOAD_BYTES) {
            throw new InvalidArgumentException('Offline mutation payload exceeds 256 KiB.');
        }
        foreach ($this->mutations as $mutation) {
            if ($mutation->key === $key && !in_array($mutation->status, [MutationStatus::Applied, MutationStatus::Failed], true)) {
                return $mutation;
            }
        }
        $mutation = new Mutation($this->nextId++, $key, $operation, $payload);
        $this->mutations[$mutation->id] = $mutation;
        return $mutation;
    }

    /** @return list<Mutation> */
    public function ready(int $nowMs, int $limit = 50): array
    {
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

    public function retry(int $id, int $nowMs, string $message): Mutation
    {
        $mutation = $this->require($id);
        $exponent = min(10, max(0, $mutation->attempts - 1));
        $delayMs = min(3_600_000, 1_000 * (2 ** $exponent));
        $updated = $mutation->withStatus(
            MutationStatus::Retry,
            self::boundedError($message),
            $nowMs + $delayMs,
        );
        return $this->mutations[$id] = $updated;
    }

    public function failed(int $id, string $message): Mutation
    {
        return $this->update($id, MutationStatus::Failed, $message);
    }

    public function prune(): int
    {
        $before = count($this->mutations);
        $this->mutations = array_filter(
            $this->mutations,
            static fn (Mutation $mutation): bool => !in_array(
                $mutation->status,
                [MutationStatus::Applied, MutationStatus::Failed],
                true,
            ),
        );
        return $before - count($this->mutations);
    }

    public function export(): string
    {
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
                ],
                array_values($this->mutations),
            ),
        ], JSON_THROW_ON_ERROR);
    }

    public static function restore(string $json): self
    {
        if ($json === '' || strlen($json) > 4_194_304) {
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
        $queue = new self();
        foreach ($data['mutations'] as $item) {
            if (!is_array($item) || !is_int($item['id'] ?? null) || !is_array($item['payload'] ?? null)) {
                throw new InvalidArgumentException('Offline mutation snapshot entry is invalid.');
            }
            $status = is_int($item['status'] ?? null) ? MutationStatus::tryFrom($item['status']) : null;
            if ($status === null || !is_string($item['key'] ?? null) || !is_string($item['operation'] ?? null)) {
                throw new InvalidArgumentException('Offline mutation snapshot entry has invalid typed fields.');
            }
            /** @var array<string, string|int|float|bool|null> $payload */
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
            );
            $queue->mutations[$mutation->id] = $mutation;
        }
        $queue->nextId = max(
            is_int($data['nextId'] ?? null) ? $data['nextId'] : 1,
            $queue->mutations === [] ? 1 : max(array_keys($queue->mutations)) + 1,
        );
        return $queue;
    }

    private function update(int $id, MutationStatus $status, ?string $error = null): Mutation
    {
        $updated = $this->require($id)->withStatus($status, $error === null ? null : self::boundedError($error));
        return $this->mutations[$id] = $updated;
    }

    private function require(int $id): Mutation
    {
        return $this->mutations[$id] ?? throw new InvalidArgumentException("Unknown offline mutation {$id}.");
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
