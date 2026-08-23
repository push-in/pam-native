<?php

declare(strict_types=1);

namespace Pam\Native\Diagnostics;

final class ReplayTimeline
{
    /** @var list<array{sequence: int, kind: int, payload: array<string, mixed>}> */
    private array $events = [];

    /** @param array<string, mixed> $payload */
    public function record(int $kind, array $payload): void
    {
        if ($kind < 1 || count($this->events) >= 10_000) {
            throw new \OverflowException('Replay timeline kind is invalid or the bounded log is full.');
        }
        $this->events[] = ['sequence' => count($this->events) + 1, 'kind' => $kind, 'payload' => $payload];
    }

    /** @param \Closure(int, array<string, mixed>): void $consumer */
    public function replay(\Closure $consumer): void
    {
        foreach ($this->events as $event) {
            $consumer($event['kind'], $event['payload']);
        }
    }

    public function fingerprint(): string
    {
        return hash('sha256', json_encode($this->events, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES));
    }
}
