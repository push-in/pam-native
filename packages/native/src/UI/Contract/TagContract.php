<?php

declare(strict_types=1);

namespace Pam\Native\UI\Contract;

use InvalidArgumentException;

final readonly class TagContract
{
    /**
     * @param list<PropContract> $props
     * @param list<EventContract> $events
     * @param list<SlotContract> $slots
     */
    public function __construct(
        public string $name,
        public array $props = [],
        public array $events = [],
        public array $slots = [],
    ) {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.-]{0,127}$/D', $name) !== 1) {
            throw new InvalidArgumentException('Tag contract names must be safe identifiers.');
        }
    }
}
