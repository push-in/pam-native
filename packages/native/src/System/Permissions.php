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
use Pam\Native\PermissionRationale;
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

    /**
     * @param Closure(PermissionDecision): void $callback
     * @param null|Closure(string): void $failure Receives the native error instead of an exception.
     */
    public static function status(PermissionKind $kind, Closure $callback, ?Closure $failure = null): int
    {
        return self::typed('status', $kind, $callback, $failure);
    }

    /**
     * Requests the kind. With a `$rationale`, when the platform says one should
     * be shown (Android after a denial), a native alert explains it first and
     * the request runs only if confirmed; cancelling answers the current status.
     *
     * @param Closure(PermissionDecision): void $callback
     * @param null|Closure(string): void $failure Receives the native error instead of an exception.
     */
    public static function requestKind(
        PermissionKind $kind,
        Closure $callback,
        ?Closure $failure = null,
        ?PermissionRationale $rationale = null,
    ): int {
        if ($rationale === null) {
            return self::typed('request', $kind, $callback, $failure);
        }

        return self::shouldShowRationale(
            $kind,
            static function (bool $show) use ($kind, $callback, $failure, $rationale): void {
                if (!$show) {
                    self::typed('request', $kind, $callback, $failure);

                    return;
                }
                Alert::choose(
                    $rationale->title,
                    $rationale->message,
                    [AlertButton::cancel($rationale->cancelLabel), AlertButton::make($rationale->confirmLabel)],
                    static function (int $pressed) use ($kind, $callback, $failure): void {
                        self::typed($pressed === 1 ? 'request' : 'status', $kind, $callback, $failure);
                    },
                    $failure,
                );
            },
            $failure,
        );
    }

    /**
     * Whether Android wants an explanation before asking again for the kind's
     * permissions (`shouldShowRequestPermissionRationale`). iOS is always false.
     *
     * @param Closure(bool): void $callback
     * @param null|Closure(string): void $failure
     */
    public static function shouldShowRationale(PermissionKind $kind, Closure $callback, ?Closure $failure = null): int
    {
        return NativeModules::call(
            'permissions',
            'shouldShowRationale',
            ['kind' => $kind->value],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $callback((bool) (Wire::decodeMap($result->payload)['show'] ?? false));
            },
        );
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

    /**
     * @param Closure(PermissionDecision): void $callback
     * @param null|Closure(string): void $failure
     */
    private static function typed(string $method, PermissionKind $kind, Closure $callback, ?Closure $failure = null): int
    {
        return NativeModules::call(
            'permissions',
            $method,
            ['kind' => $kind->value],
            static function ($result) use ($kind, $callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
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
