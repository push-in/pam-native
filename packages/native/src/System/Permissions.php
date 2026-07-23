<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;
use RuntimeException;

final class Permissions
{
    private function __construct()
    {
    }

    /** @param Closure(bool): void $callback */
    public static function check(string $permission, Closure $callback): int
    {
        return self::call(NativeOperation::PermissionCheck, $permission, $callback);
    }

    /** @param Closure(bool): void $callback */
    public static function request(string $permission, Closure $callback): int
    {
        return self::call(NativeOperation::PermissionRequest, $permission, $callback);
    }

    /** @param Closure(bool): void $callback */
    private static function call(
        NativeOperation $operation,
        string $permission,
        Closure $callback,
    ): int {
        return Runtime::callNative(
            $operation,
            Wire::map(['permission' => $permission]),
            static function (ModuleResultStatus $status, string $payload) use ($callback): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $values = Wire::decodeMap($payload);
                $callback((bool) ($values['granted'] ?? false));
            },
        );
    }
}
