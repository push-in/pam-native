<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

final class NavigationEvent
{
    private bool $defaultPrevented = false;

    /** @param array<string, mixed> $data */
    public function __construct(
        public readonly NavigationEventType $type,
        public readonly string $target,
        public readonly array $data = [],
        public readonly bool $canPreventDefault = false,
    ) {
    }

    public function preventDefault(): void
    {
        if ($this->canPreventDefault) {
            $this->defaultPrevented = true;
        }
    }

    public function isDefaultPrevented(): bool
    {
        return $this->defaultPrevented;
    }
}
