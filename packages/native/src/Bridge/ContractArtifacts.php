<?php

declare(strict_types=1);

namespace Pam\Native\Bridge;

final readonly class ContractArtifacts
{
    /** @param array<string, mixed> $manifest */
    public function __construct(
        public array $manifest,
        public string $php,
        public string $kotlin,
        public string $swift,
        public string $fingerprint,
    ) {
    }
}
