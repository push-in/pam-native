<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class ProtocolCompatibilityReport
{
    /** @param list<string> $capabilities */
    private function __construct(
        public ProtocolCompatibilityStatus $status,
        public ?int $protocolVersion,
        public array $capabilities,
        public string $message,
    ) {
    }

    /** @param list<string> $capabilities */
    public static function compatible(int $protocolVersion, array $capabilities): self
    {
        return new self(ProtocolCompatibilityStatus::Compatible, $protocolVersion, $capabilities, 'Compatible.');
    }

    public static function incompatible(ProtocolCompatibilityStatus $status, string $message): self
    {
        return new self($status, null, [], $message);
    }

    public function isCompatible(): bool
    {
        return $this->status === ProtocolCompatibilityStatus::Compatible;
    }

    /** @param list<string> $required */
    public function requireCapabilities(array $required): self
    {
        $missing = array_values(array_diff($required, $this->capabilities));
        if ($missing === []) {
            return $this;
        }

        return self::incompatible(
            ProtocolCompatibilityStatus::MissingCapability,
            'Missing capabilities: '.implode(', ', $missing).'.',
        );
    }
}
