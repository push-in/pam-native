<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class ComponentChanges
{
    /** @param array<string, array{previous: mixed, current: mixed}> $values */
    public function __construct(
        public array $values,
    ) {
    }

    public function changed(string ...$properties): bool
    {
        foreach ($properties as $property) {
            if (array_key_exists($property, $this->values)) {
                return true;
            }
        }

        return false;
    }

    public function any(): bool
    {
        return $this->values !== [];
    }
}
