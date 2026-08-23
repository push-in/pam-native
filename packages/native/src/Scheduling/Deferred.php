<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

final class Deferred
{
    private bool $settled = false;
    private mixed $value = null;
    private ?\Throwable $error = null;
    /** @var list<\Closure(mixed, ?\Throwable): void> */
    private array $listeners = [];

    public function resolve(mixed $value = null): void
    {
        $this->settle($value, null);
    }

    public function reject(\Throwable $error): void
    {
        $this->settle(null, $error);
    }

    /** @param \Closure(mixed, ?\Throwable): void $listener */
    public function subscribe(\Closure $listener): void
    {
        if ($this->settled) {
            $listener($this->value, $this->error);
            return;
        }
        $this->listeners[] = $listener;
    }

    public function await(?CancellationToken $token = null, ?int $deadlineMs = null): mixed
    {
        $started = (int) floor(microtime(true) * 1_000);
        while (!$this->settled) {
            $token?->throwIfCancelled();
            if ($deadlineMs !== null && (int) floor(microtime(true) * 1_000) - $started >= $deadlineMs) {
                throw new DeadlineExceededException();
            }
            if (Scheduler::drain(1.0) === 0) {
                usleep(500);
            }
        }
        if ($deadlineMs !== null && (int) floor(microtime(true) * 1_000) - $started >= $deadlineMs) {
            throw new DeadlineExceededException();
        }
        if ($this->error !== null) {
            throw $this->error;
        }
        return $this->value;
    }

    private function settle(mixed $value, ?\Throwable $error): void
    {
        if ($this->settled) {
            throw new \LogicException('Deferred values can only be settled once.');
        }
        $this->settled = true;
        $this->value = $value;
        $this->error = $error;
        $listeners = $this->listeners;
        $this->listeners = [];
        foreach ($listeners as $listener) {
            $listener($value, $error);
        }
    }
}
