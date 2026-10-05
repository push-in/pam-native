<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class DeviceStatus
{
    public function __construct(
        public float $batteryLevel,
        public bool $charging,
        public NetworkType $networkType,
        public bool $expensiveNetwork,
        public bool $lowPowerMode,
    ) {
    }

    /** Android battery saver / iOS Low Power Mode. Alias of $lowPowerMode. */
    public function powerSaveMode(): bool
    {
        return $this->lowPowerMode;
    }
}
