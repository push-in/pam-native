<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use Pam\Native\Scheduling\CancellationToken;
use Pam\Native\Scheduling\ScheduledTask;
use Pam\Native\Scheduling\Scheduler;
use Pam\Native\Scheduling\TaskPriority;
use Throwable;

final class AsyncResource
{
    private AsyncValue $value;
    private ?ScheduledTask $task = null;
    /** @var list<Closure(AsyncValue): void> */
    private array $listeners = [];

    /** @param Closure(CancellationToken): mixed $loader */
    public function __construct(
        private readonly Closure $loader,
        private readonly ?string $key = null,
    ) {
        $this->value = AsyncValue::loading();
    }

    public function value(): AsyncValue
    {
        return $this->value;
    }

    public function load(TaskPriority $priority = TaskPriority::Normal): ScheduledTask
    {
        $previous = $this->value->data;
        $this->set(AsyncValue::loading($previous));
        $this->task?->cancel();
        $this->task = Scheduler::schedule(
            function (CancellationToken $token) use ($previous): void {
                try {
                    $result = ($this->loader)($token);
                    $token->throwIfCancelled();
                    $this->set($result === null || $result === []
                        ? AsyncValue::empty()
                        : AsyncValue::content($result));
                } catch (Scheduling\TaskCancelledException) {
                    return;
                } catch (Throwable $error) {
                    $this->set(AsyncValue::error($error->getMessage(), previous: $previous));
                }
            },
            $priority,
            $this->key,
        );
        return $this->task;
    }

    /** @param Closure(AsyncValue): void $listener */
    public function subscribe(Closure $listener): Closure
    {
        $this->listeners[] = $listener;
        $index = array_key_last($this->listeners);
        return function () use ($index): void {
            unset($this->listeners[$index]);
        };
    }

    public function cancel(): void
    {
        $this->task?->cancel();
    }

    private function set(AsyncValue $value): void
    {
        $this->value = $value;
        foreach ($this->listeners as $listener) {
            $listener($value);
        }
    }
}
