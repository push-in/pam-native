<?php

declare(strict_types=1);

namespace Pam\Native\Scheduling;

final class AsyncStream
{
    /** @var list<mixed> */
    private array $buffer = [];
    private bool $closed = false;

    public function __construct(private readonly int $capacity = 64)
    {
        if ($capacity < 1 || $capacity > 4_096) {
            throw new \InvalidArgumentException('Stream capacity must be between 1 and 4,096.');
        }
    }

    public function emit(mixed $value): void
    {
        if ($this->closed) {
            throw new \LogicException('Cannot emit into a closed stream.');
        }
        if (count($this->buffer) >= $this->capacity) {
            throw new BackpressureException($this->capacity);
        }
        $this->buffer[] = $value;
    }

    public function next(): mixed
    {
        if ($this->buffer === []) {
            return null;
        }
        return array_shift($this->buffer);
    }

    public function close(): void
    {
        $this->closed = true;
    }

    public function pending(): int
    {
        return count($this->buffer);
    }
}
