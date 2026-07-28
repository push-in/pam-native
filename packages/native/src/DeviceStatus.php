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
}
