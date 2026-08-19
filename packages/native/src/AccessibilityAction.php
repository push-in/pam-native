<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;

final readonly class AccessibilityAction
{
    public function __construct(
        public string $name,
        public string $label,
    ) {
        if (preg_match('/^[a-z][a-z0-9._-]{0,63}$/', $name) !== 1) {
            throw new InvalidArgumentException(
                'Accessibility action names must be lowercase safe identifiers up to 64 bytes.',
            );
        }
        if ($label === '' || strlen($label) > 128 || preg_match('//u', $label) !== 1) {
            throw new InvalidArgumentException(
                'Accessibility action labels must be valid UTF-8 between 1 and 128 bytes.',
            );
        }
    }

    /** @return array{name: string, label: string} */
    public function toArray(): array
    {
        return ['name' => $this->name, 'label' => $this->label];
    }
}
