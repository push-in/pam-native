<?php

declare(strict_types=1);

namespace Pam\Native\Plugin;

final class PluginRegistry
{
    /** @var array<string, RegistryEntry> */
    private array $entries = [];

    public function register(RegistryEntry $entry): void
    {
        $current = $this->entries[$entry->package] ?? null;
        if ($current !== null && version_compare($entry->version, $current->version, '<=')) {
            throw new \LogicException('Plugin registry versions are immutable and monotonically increasing.');
        }
        $this->entries[$entry->package] = $entry;
    }

    /** @param list<string> $grantedCapabilities */
    public function authorize(
        string $package,
        array $grantedCapabilities,
        int $minimumScore = 70,
        TrustTier $minimumTrust = TrustTier::Community,
    ): RegistryEntry
    {
        $entry = $this->entries[$package] ?? throw new PluginException("Plugin {$package} is not registered.");
        if ($entry->qualityScore < $minimumScore
            || $entry->trust->value < $minimumTrust->value
            || array_diff($entry->capabilities, $grantedCapabilities) !== []) {
            throw new PluginException("Plugin {$package} does not satisfy trust, quality, or capability policy.");
        }
        return $entry;
    }
}
