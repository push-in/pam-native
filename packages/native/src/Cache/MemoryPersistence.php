<?php

declare(strict_types=1);

namespace Pam\Native\Cache;

use Closure;

/** Process-local persistence for tests and previews. */
final class MemoryPersistence implements CachePersistence
{
    /** @var array<string, string> */
    public array $values = [];

    public function __construct(private readonly int $maxValueBytes = 2_097_152)
    {
    }

    public function maxValueBytes(): int
    {
        return $this->maxValueBytes;
    }

    public function load(string $key, Closure $done): void
    {
        $done($this->values[$key] ?? null);
    }

    public function save(string $key, string $value, ?Closure $done = null, ?Closure $failed = null): void
    {
        $this->values[$key] = $value;
        $done?->__invoke();
    }
}
