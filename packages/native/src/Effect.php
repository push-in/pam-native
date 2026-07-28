<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;

final readonly class Effect
{
    /** @param Closure(): mixed $dependencies @param Closure(mixed): (Closure(): void)|null $run */
    public function __construct(
        public Closure $dependencies,
        public Closure $run,
        public bool $once = false,
    ) {
    }

    public static function watch(Closure $dependencies, Closure $run): self
    {
        return new self($dependencies, $run);
    }

    public static function once(Closure $run): self
    {
        return new self(static fn (): array => [], $run, true);
    }
}
