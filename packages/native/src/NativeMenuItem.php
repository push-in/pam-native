<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;

final readonly class NativeMenuItem
{
    public function __construct(
        public string $id,
        public string $title,
        public bool $destructive = false,
        public bool $disabled = false,
    ) {
        if (
            preg_match('/^[A-Za-z0-9_.:-]{1,128}$/D', $id) !== 1
            || $title === ''
            || strlen($title) > 256
        ) {
            throw new InvalidArgumentException('Native menu item is invalid.');
        }
    }
}
