<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\AppState;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;
use Pam\Native\UserInterfaceAppearance;
use RuntimeException;

final readonly class DeviceInfo
{
    public function __construct(
        public float $width,
        public float $height,
        public float $density,
        public UserInterfaceAppearance $appearance,
        public AppState $appState,
    ) {
    }

    /** @param Closure(self): void $callback */
    public static function get(Closure $callback): int
    {
        return Runtime::callNative(
            NativeOperation::DeviceInfo,
            '',
            static function (ModuleResultStatus $status, string $payload) use ($callback): void {
                if ($status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($payload);
                }

                $values = Wire::decodeMap($payload);
                $callback(new self(
                    width: (float) ($values['width'] ?? 0.0),
                    height: (float) ($values['height'] ?? 0.0),
                    density: (float) ($values['density'] ?? 1.0),
                    appearance: UserInterfaceAppearance::from((int) ($values['appearance'] ?? 1)),
                    appState: AppState::from((int) ($values['appState'] ?? 1)),
                ));
            },
        );
    }
}
