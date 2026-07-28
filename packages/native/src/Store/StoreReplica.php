<?php

declare(strict_types=1);

namespace Pam\Native\Store;

use Closure;

final class StoreReplica
{
    private ?int $subscription = null;

    /** @param Closure(string, int, array<string, mixed>): void $writer */
    public function __construct(
        private readonly Store $store,
        private readonly Closure $writer,
    ) {
    }

    public function start(): void
    {
        if ($this->subscription !== null) {
            return;
        }
        $this->subscription = $this->store->subscribe(function (): void {
            ($this->writer)(
                $this->store->key(),
                $this->store->__pamVersion(),
                $this->store->snapshot(),
            );
        });
    }

    public function stop(): void
    {
        if ($this->subscription === null) {
            return;
        }
        $this->store->unsubscribe($this->subscription);
        $this->subscription = null;
    }

    /** @param array<string, mixed> $remote @param Closure(array<string, mixed>, array<string, mixed>): array<string, mixed>|null $merge */
    public function merge(array $remote, ?Closure $merge = null): void
    {
        $local = $this->store->snapshot();
        $state = $merge === null ? array_replace($local, $remote) : $merge($local, $remote);
        $this->store->transaction(function () use ($state, $local): void {
            foreach ($state as $key => $value) {
                if (array_key_exists($key, $local)) {
                    $this->store->{$key} = $value;
                }
            }
        }, 'replica:merge');
    }
}
