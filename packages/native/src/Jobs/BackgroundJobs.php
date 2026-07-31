<?php

declare(strict_types=1);

namespace Pam\Native\Jobs;

use Closure;
use InvalidArgumentException;
use Pam\Native\Scheduling\CancellationToken;
use Pam\Native\Scheduling\Scheduler;
use Pam\Native\Scheduling\TaskPriority;
use Pam\Native\Sync\Mutation;
use Pam\Native\Sync\OfflineMutationQueue;
use Throwable;

final class BackgroundJobs
{
    /** @var array<string, Closure(array<string, string|int|float|bool|null>, CancellationToken): void> */
    private array $handlers = [];

    public function __construct(private readonly OfflineMutationQueue $queue = new OfflineMutationQueue())
    {
    }

    /** @param Closure(array<string, string|int|float|bool|null>, CancellationToken): void $handler */
    public function register(string $name, Closure $handler): void
    {
        self::name($name);
        if (isset($this->handlers[$name])) {
            throw new InvalidArgumentException("Background job {$name} is already registered.");
        }
        $this->handlers[$name] = $handler;
    }

    /** @param array<string, string|int|float|bool|null> $payload */
    public function dispatch(string $name, string $uniqueKey, array $payload = []): Mutation
    {
        self::name($name);
        if (!isset($this->handlers[$name])) {
            throw new InvalidArgumentException("Background job {$name} is not registered.");
        }
        return $this->queue->enqueue($uniqueKey, 'job:'.$name, $payload);
    }

    public function runReady(int $nowMs, int $limit = 20): int
    {
        $scheduled = 0;
        foreach ($this->queue->ready($nowMs, $limit) as $mutation) {
            $name = substr($mutation->operation, 4);
            $handler = $this->handlers[$name] ?? null;
            if ($handler === null) {
                $this->queue->failed($mutation->id, "Background job {$name} is not registered.");
                continue;
            }
            $this->queue->sending($mutation->id);
            Scheduler::schedule(
                function (CancellationToken $token) use ($handler, $mutation, $nowMs): void {
                    try {
                        $handler($mutation->payload, $token);
                        $token->throwIfCancelled();
                        $this->queue->applied($mutation->id);
                    } catch (\Pam\Native\Scheduling\TaskCancelledException) {
                        $this->queue->retry($mutation->id, $nowMs, 'Background job was cancelled.');
                    } catch (Throwable $error) {
                        $this->queue->retry($mutation->id, $nowMs, $error->getMessage());
                    }
                },
                TaskPriority::Background,
                'background-job:'.$mutation->key,
            );
            $scheduled++;
        }
        return $scheduled;
    }

    public function snapshot(): string
    {
        return $this->queue->export();
    }

    public static function restore(string $snapshot): self
    {
        return new self(OfflineMutationQueue::restore($snapshot));
    }

    private static function name(string $name): void
    {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/', $name) !== 1) {
            throw new InvalidArgumentException('Background job names must be safe identifiers.');
        }
    }
}
