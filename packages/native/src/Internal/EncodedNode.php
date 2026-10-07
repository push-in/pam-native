<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use function chr;
use function count;

final class EncodedNode
{
    /**
     * @param array<int, string> $properties encoded values by property key
     * @param string|null $propertyBytes u16 count followed by key/value pairs;
     *        computed once so reused nodes never re-serialize their values
     */
    public function __construct(
        public readonly int $id,
        public readonly int $parent,
        public readonly int $index,
        public readonly int $kind,
        public readonly array $properties,
        public ?string $propertyBytes = null,
    ) {
    }

    public function hasSameTopology(self $other): bool
    {
        return $this->parent === $other->parent
            && $this->index === $other->index
            && $this->kind === $other->kind;
    }

    /** Same node at a new parent/sibling position. */
    public function placedAt(int $parent, int $index): self
    {
        return new self($this->id, $parent, $index, $this->kind, $this->properties, $this->propertyBytes);
    }

    public function bytes(): string
    {
        if ($this->propertyBytes === null) {
            $bytes = Wire::u16(count($this->properties));
            foreach ($this->properties as $key => $value) {
                $bytes .= (self::$keys[$key] ??= pack('v', $key)).$value;
            }
            $this->propertyBytes = $bytes;
        }

        return pack('PPV', $this->id, $this->parent, $this->index)
            .chr($this->kind)
            .$this->propertyBytes;
    }

    /** @var array<int, string> little-endian u16 property keys */
    private static array $keys = [];
}
