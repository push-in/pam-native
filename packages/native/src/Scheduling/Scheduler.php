<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

use Closure;
use Pam\Native\Diagnostics\Profiler;
use SplPriorityQueue;

final class Scheduler
{
    private const MAX_QUEUE = 10_000;

    private static ?SplPriorityQueue $queue = null;
    /** @var array<string, ScheduledTask> */
    private static array $coalesced = [];
    private static int $nextId = 1;
    private static int $sequence = 0;
    private static bool $draining = false;

    private function __construct()
    {
    }

    /** @param Closure(CancellationToken): void $callback */
    public static function schedule(
        Closure $callback,
        TaskPriority $priority = TaskPriority::Normal,
        ?string $coalesce = null,
    ): ScheduledTask {
        $queue = self::$queue ??= new SplPriorityQueue();
        $queue->setExtractFlags(SplPriorityQueue::EXTR_DATA);
        if ($queue->count() >= self::MAX_QUEUE) {
            throw new \OverflowException('Pam scheduler queue exceeded 10,000 tasks.');
        }
        if ($coalesce !== null && isset(self::$coalesced[$coalesce])) {
            self::$coalesced[$coalesce]->cancel();
        }
        $task = new ScheduledTask(self::$nextId++, new CancellationToken());
        if ($coalesce !== null) {
            self::$coalesced[$coalesce] = $task;
        }
        $queue->insert(
            [
                'task' => $task,
                'callback' => $callback,
                'coalesce' => $coalesce,
                'priority' => $priority,
            ],
            [-$priority->value, -self::$sequence++],
        );

        return $task;
    }

    public static function drain(float $budgetMs = 8.0): int
    {
        if (self::$draining) {
            return 0;
        }
        self::$draining = true;
        $started = hrtime(true);
        $executed = 0;
        try {
            $queue = self::$queue;
            while ($queue !== null && !$queue->isEmpty()) {
                $entry = $queue->extract();
                $task = $entry['task'];
                if ($task->token->cancelled()) {
                    continue;
                }
                $coalesce = $entry['coalesce'];
                if ($coalesce !== null && (self::$coalesced[$coalesce]->id ?? null) === $task->id) {
                    unset(self::$coalesced[$coalesce]);
                }
                Profiler::measure(
                    'scheduler.task',
                    fn () => $entry['callback']($task->token),
                    ['priority' => $entry['priority']->value],
                );
                $executed++;
                $elapsedMs = (hrtime(true) - $started) / 1_000_000;
                if ($elapsedMs >= $budgetMs) {
                    break;
                }
            }
        } finally {
            self::$draining = false;
        }

        return $executed;
    }

    public static function pending(): int
    {
        return self::$queue?->count() ?? 0;
    }

    public static function reset(): void
    {
        self::$queue = null;
        self::$coalesced = [];
        self::$nextId = 1;
        self::$sequence = 0;
        self::$draining = false;
    }
}
