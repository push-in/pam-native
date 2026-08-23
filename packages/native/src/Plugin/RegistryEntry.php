<?php

declare(strict_types=1);

namespace Pam\Native\Plugin;

final readonly class RegistryEntry
{
    /** @param list<string> $capabilities */
    public function __construct(
        public string $package,
        public string $version,
        public TrustTier $trust,
        public array $capabilities,
        public string $sha256,
        public int $qualityScore,
    ) {
        if (preg_match('/^[a-z0-9_.-]+\/[a-z0-9_.-]+$/D', $package) !== 1
            || preg_match('/^[0-9]+\.[0-9]+\.[0-9]+$/D', $version) !== 1
            || preg_match('/^[a-f0-9]{64}$/D', $sha256) !== 1
            || $qualityScore < 0 || $qualityScore > 100) {
            throw new \InvalidArgumentException('Plugin registry entry is invalid.');
        }
        if (count($capabilities) !== count(array_unique($capabilities))) {
            throw new \InvalidArgumentException('Plugin capabilities must be unique.');
        }
        foreach ($capabilities as $capability) {
            if (preg_match('/^[a-z][a-z0-9.-]{0,63}$/D', $capability) !== 1) {
                throw new \InvalidArgumentException('Plugin capabilities must use portable dotted identifiers.');
            }
        }
    }
}
