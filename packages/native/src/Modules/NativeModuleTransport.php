<?php

declare(strict_types=1);

namespace Pam\Native\Modules;

use Closure;
use Pam\Native\ModuleResultStatus;

interface NativeModuleTransport
{
    /** @param Closure(ModuleResultStatus, string): void $complete */
    public function invoke(
        int $requestId,
        string $module,
        string $method,
        string $payload,
        Closure $complete,
    ): void;
}
