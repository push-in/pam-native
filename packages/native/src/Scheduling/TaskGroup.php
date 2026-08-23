<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

final class TaskGroup
{
    private readonly CancellationToken $token;
    /** @var list<Deferred> */
    private array $tasks = [];

    public function __construct(private readonly ?int $deadlineMs = null)
    {
        if ($deadlineMs !== null && ($deadlineMs < 1 || $deadlineMs > 3_600_000)) {
            throw new \InvalidArgumentException('Task group deadline must be between 1 ms and one hour.');
        }
        $this->token = new CancellationToken();
    }

    /** @param \Closure(CancellationToken): mixed $work */
    public function async(\Closure $work, TaskPriority $priority = TaskPriority::Normal): Deferred
    {
        $deferred = new Deferred();
        $this->tasks[] = $deferred;
        Scheduler::schedule(function (CancellationToken $scheduled) use ($work, $deferred): void {
            try {
                $this->token->throwIfCancelled();
                $scheduled->throwIfCancelled();
                $deferred->resolve($work($this->token));
            } catch (\Throwable $error) {
                $this->token->cancel();
                $deferred->reject($error);
            }
        }, $priority);
        return $deferred;
    }

    /** @return list<mixed> */
    public function awaitAll(): array
    {
        $started = (int) floor(microtime(true) * 1_000);
        try {
            $results = [];
            foreach ($this->tasks as $task) {
                $remaining = $this->deadlineMs;
                if ($remaining !== null) {
                    $remaining -= (int) floor(microtime(true) * 1_000) - $started;
                    if ($remaining < 1) {
                        throw new DeadlineExceededException();
                    }
                }
                $results[] = $task->await($this->token, $remaining);
            }
            return $results;
        } catch (\Throwable $error) {
            $this->cancel();
            throw $error;
        }
    }

    public function cancel(): void
    {
        $this->token->cancel();
    }
}
