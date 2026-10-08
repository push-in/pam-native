<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use Pam\Native\BiometricError;
use Pam\Native\BiometricKind;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;

/**
 * System biometric prompt: Android BiometricPrompt (Android 10+, weak or
 * strong biometrics) and iOS LocalAuthentication (Face ID / Touch ID; the app
 * must declare NSFaceIDUsageDescription). Pair it with SecureStorage for
 * anything that must stay on the device.
 */
final class Biometrics
{
    private function __construct()
    {
    }

    /** @param Closure(BiometricKind): void $callback BiometricKind::None when unavailable or not enrolled. */
    public static function status(Closure $callback): int
    {
        return NativeModules::call('biometrics', 'status', [], static function ($result) use ($callback): void {
            if ($result->status === ModuleResultStatus::Failure) {
                $callback(BiometricKind::None);

                return;
            }
            $values = Wire::decodeMap($result->payload);
            $callback(BiometricKind::tryFrom((int) ($values['kind'] ?? 1)) ?? BiometricKind::None);
        });
    }

    /**
     * Shows the system prompt.
     *
     * @param Closure(bool, BiometricError): void $callback true only when the user passed it
     */
    public static function authenticate(string $title, string $cancelLabel, Closure $callback, string $subtitle = ''): int
    {
        if (trim($title) === '' || trim($cancelLabel) === '') {
            throw new InvalidArgumentException('Biometric prompts need a title and a cancel label.');
        }

        return NativeModules::call(
            'biometrics',
            'authenticate',
            ['title' => $title, 'cancelLabel' => $cancelLabel, 'subtitle' => $subtitle],
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    $callback(false, BiometricError::Unavailable);

                    return;
                }
                $values = Wire::decodeMap($result->payload);
                $callback(
                    ($values['authenticated'] ?? false) === true,
                    BiometricError::tryFrom((int) ($values['error'] ?? 3)) ?? BiometricError::Unavailable,
                );
            },
        );
    }
}
