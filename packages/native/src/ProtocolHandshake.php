<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;

final readonly class ProtocolHandshake
{
    /** @param list<string> $capabilities */
    public function __construct(
        public int $abiVersion,
        public int $minimumProtocolVersion,
        public int $maximumProtocolVersion,
        public array $capabilities,
    ) {
        if ($abiVersion < 1 || $minimumProtocolVersion < 1 || $maximumProtocolVersion < $minimumProtocolVersion) {
            throw new InvalidArgumentException('Protocol handshake versions are invalid.');
        }
        if (count($capabilities) > 256 || $capabilities !== array_values(array_unique($capabilities))) {
            throw new InvalidArgumentException('Protocol capabilities must be a unique bounded list.');
        }
        foreach ($capabilities as $capability) {
            if (preg_match('/^[a-z][a-z0-9]*(?:[._-][a-z0-9]+){1,7}$/D', $capability) !== 1) {
                throw new InvalidArgumentException("Invalid protocol capability {$capability}.");
            }
        }
    }

    public function supports(string $capability): bool
    {
        return in_array($capability, $this->capabilities, true);
    }
}
