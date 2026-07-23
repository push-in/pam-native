<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

final readonly class EncodedNode
{
    /**
     * @param array<int, string> $properties
     */
    public function __construct(
        public int $id,
        public int $parent,
        public int $index,
        public int $kind,
        public array $properties,
    ) {
    }

    public function hasSameTopology(self $other): bool
    {
        return $this->parent === $other->parent
            && $this->index === $other->index
            && $this->kind === $other->kind;
    }
}
