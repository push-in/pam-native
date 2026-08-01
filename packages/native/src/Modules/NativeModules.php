<?php

declare(strict_types=1);

namespace Pam\Native\Modules;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;

final class NativeModules
{
    private function __construct()
    {
    }

    /**
     * Installs an alternate module transport, primarily for deterministic
     * hosts and the official testing package. Pass null to restore native I/O.
     */
    public static function useTransport(?NativeModuleTransport $transport): void
    {
        Runtime::setModuleTransport($transport);
    }

    /**
     * @param array<string, string|int|float|bool> $payload
     * @param Closure(NativeModuleResult): void $callback
     */
    public static function call(
        string $module,
        string $method,
        array $payload,
        Closure $callback,
    ): int {
        return self::callRaw($module, $method, Wire::map($payload), $callback);
    }

    /**
     * @param Closure(NativeModuleResult): void $callback
     */
    public static function callRaw(
        string $module,
        string $method,
        string $payload,
        Closure $callback,
    ): int {
        return Runtime::call(
            $module,
            $method,
            $payload,
            static function (ModuleResultStatus $status, string $result) use ($callback): void {
                $callback(new NativeModuleResult($status, $result));
            },
        );
    }
}
