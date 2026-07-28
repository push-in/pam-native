<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class PushToken
{
    public function __construct(
        public string $value,
        public PushProvider $provider,
    ) {
    }
}
