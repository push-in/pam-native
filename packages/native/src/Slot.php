<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class Slot
{
    private function __construct(
        public int $minimum,
        public ?int $maximum,
    ) {
    }

    public static function optional(): self
    {
        return new self(0, 1);
    }

    public static function required(): self
    {
        return new self(1, 1);
    }

    public static function multiple(int $minimum = 0): self
    {
        return new self($minimum, null);
    }
}
