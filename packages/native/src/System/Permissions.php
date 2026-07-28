<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\NativeOperation;
use Pam\Native\PermissionDecision;
use Pam\Native\PermissionKind;
use Pam\Native\PermissionStatus;
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

    /** @param Closure(PermissionDecision): void $callback */
    public static function status(PermissionKind $kind, Closure $callback): int
    {
        return self::typed('status', $kind, $callback);
    }

    /** @param Closure(PermissionDecision): void $callback */
    public static function requestKind(PermissionKind $kind, Closure $callback): int
    {
        return self::typed('request', $kind, $callback);
    }

    public static function openSettings(?Closure $callback = null): int
    {
        return NativeModules::call(
            'permissions',
            'openSettings',
            [],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $callback?->__invoke();
            },
        );
    }

    /** @param Closure(PermissionDecision): void $callback */
    private static function typed(string $method, PermissionKind $kind, Closure $callback): int
    {
        return NativeModules::call(
            'permissions',
            $method,
            ['kind' => $kind->value],
            static function ($result) use ($kind, $callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $values = Wire::decodeMap($result->payload);
                $callback(new PermissionDecision(
                    kind: $kind,
                    status: PermissionStatus::from((int) ($values['status'] ?? 2)),
                    canAskAgain: (bool) ($values['canAskAgain'] ?? false),
                ));
            },
        );
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
