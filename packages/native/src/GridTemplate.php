<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;

final readonly class GridTemplate
{
    /** @var list<GridBreakpoint> */
    public array $levels;

    public function __construct(GridBreakpoint ...$levels)
    {
        $levels = array_values($levels);
        if ($levels === [] || count($levels) > 6 || $levels[0]->minimumWidth !== 0.0) {
            throw new InvalidArgumentException('Grid templates require one to six levels starting at zero.');
        }
        $previous = -1.0;
        foreach ($levels as $level) {
            // The shared engine compares float32 thresholds, not PHP doubles.
            $decoded = unpack('fwidth', pack('f', $level->minimumWidth));
            $width = $decoded === false ? null : $decoded['width'];
            if (!is_float($width) || $width <= $previous) {
                throw new InvalidArgumentException('Grid thresholds must be strictly increasing.');
            }
            $previous = $width;
        }
        $this->levels = $levels;
        if (strlen($this->toWire()) > 1024) {
            throw new InvalidArgumentException('Grid template exceeds the native wire limit.');
        }
    }

    public function toWire(): string
    {
        return implode(';', array_map(static fn (GridBreakpoint $level): string => $level->toWire(), $this->levels));
    }

    public static function fromWire(string $wire): self
    {
        if ($wire === '' || strlen($wire) > 1024 || substr_count($wire, ';') > 5) {
            throw new InvalidArgumentException('Invalid grid template shape.');
        }
        $levels = [];
        foreach (explode(';', $wire) as $row) {
            $fields = explode(',', $row);
            if (count($fields) !== 4 || !ctype_digit($fields[1])
                || !is_numeric($fields[0]) || !is_numeric($fields[2]) || !is_numeric($fields[3])) {
                throw new InvalidArgumentException('Invalid grid template level.');
            }
            $levels[] = new GridBreakpoint((float) $fields[0], (int) $fields[1], (float) $fields[2], (float) $fields[3]);
        }
        return new self(...$levels);
    }
}
