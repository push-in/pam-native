<?php

declare(strict_types=1);

namespace Pam\Native\Bridge;

final readonly class IdlArtifacts
{
    public function __construct(
        public string $php,
        public string $kotlin,
        public string $swift,
        public string $rust,
        public string $fingerprint,
    ) {
    }
}
