<?php

declare(strict_types=1);

namespace Pam\Native\Cache;

use Closure;

/** Process-local persistence for tests and previews. */
final class MemoryPersistence implements CachePersistence
{
    /** @var array<string, string> */
    public array $values = [];

    public function load(string $key, Closure $done): void
    {
        $done($this->values[$key] ?? null);
    }

    public function save(string $key, string $value, ?Closure $done = null): void
    {
        $this->values[$key] = $value;
        $done?->__invoke();
    }
}
