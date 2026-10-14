<?php

declare(strict_types=1);

namespace Pam\Native\Cache;

use Closure;

/**
 * Adapts any key-value backend:
 *
 *     new CallbackPersistence(
 *         load: fn (string $key, Closure $done) => $done($kv->getString($key)),
 *         save: fn (string $key, string $value, ?Closure $done) => [$kv->set($key, $value), $done?->__invoke()],
 *     );
 *
 * Synchronous backends call $done before returning; asynchronous ones later.
 */
final class CallbackPersistence implements CachePersistence
{
    /**
     * @param Closure(string, Closure(?string): void): void $load
     * @param Closure(string, string, (Closure(): void)|null): void $save
     */
    public function __construct(private readonly Closure $load, private readonly Closure $save)
    {
    }

    public function load(string $key, Closure $done): void
    {
        ($this->load)($key, $done);
    }

    public function save(string $key, string $value, ?Closure $done = null): void
    {
        ($this->save)($key, $value, $done);
    }
}
