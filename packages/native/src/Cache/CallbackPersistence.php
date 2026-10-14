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
 * $save may receive a fourth argument, $failed(Throwable), to report a failed write.
 */
final class CallbackPersistence implements CachePersistence
{
    /**
     * @param Closure(string, Closure(?string): void): void $load
     * @param Closure(string, string, (Closure(): void)|null, (Closure(\Throwable): void)|null): void $save
     */
    public function __construct(
        private readonly Closure $load,
        private readonly Closure $save,
        private readonly int $maxValueBytes = 2_097_152,
    ) {
    }

    public function maxValueBytes(): int
    {
        return $this->maxValueBytes;
    }

    public function load(string $key, Closure $done): void
    {
        ($this->load)($key, $done);
    }

    public function save(string $key, string $value, ?Closure $done = null, ?Closure $failed = null): void
    {
        try {
            ($this->save)($key, $value, $done, $failed);
        } catch (\Throwable $error) {
            if ($failed === null) {
                throw $error;
            }
            $failed($error);
        }
    }
}
