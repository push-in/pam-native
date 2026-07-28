<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class ErrorContext
{
    public function __construct(
        public string $component,
        public string $phase,
        public int $attempt,
    ) {
    }
}
