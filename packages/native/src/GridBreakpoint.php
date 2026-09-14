<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;

final readonly class GridBreakpoint
{
    public function __construct(
        public float $minimumWidth,
        public int $columns,
        public float $columnGap = 0.0,
        public float $rowGap = 0.0,
    ) {
        foreach ([$minimumWidth, $columnGap, $rowGap] as $dimension) {
            if (!is_finite($dimension) || $dimension < 0.0 || $dimension > 3.402823466e38) {
                throw new InvalidArgumentException('Grid dimensions must be finite, nonnegative float32 values.');
            }
        }
        if ($columns < 1 || $columns > 64) {
            throw new InvalidArgumentException('Grid columns must be between 1 and 64.');
        }
    }

    public function toWire(): string
    {
        return implode(',', array_map(
            static fn (int|float $value): string => json_encode($value, JSON_THROW_ON_ERROR),
            [$this->minimumWidth, $this->columns, $this->columnGap, $this->rowGap],
        ));
    }
}
