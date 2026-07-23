<?php

declare(strict_types=1);

namespace Pam\Native\Modules;

use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;

final readonly class NativeModuleResult
{
    public function __construct(
        public ModuleResultStatus $status,
        public string $payload,
    ) {
    }

    public function succeeded(): bool
    {
        return $this->status === ModuleResultStatus::Success;
    }

    /** @return array<string, string|int|float|bool> */
    public function values(): array
    {
        return Wire::decodeMap($this->payload);
    }

    public function message(): string
    {
        return $this->payload;
    }
}
