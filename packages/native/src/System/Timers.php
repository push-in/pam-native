<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class Timers
{
    private const MAX_DELAY_MS = 86_400_000;

    private function __construct()
    {
    }

    /** @param Closure(): void $callback */
    public static function after(
        int $milliseconds,
        Closure $callback,
        ?Closure $failure = null,
    ): int {
        if ($milliseconds < 0 || $milliseconds > self::MAX_DELAY_MS) {
            throw new InvalidArgumentException(
                'Timer delay must be between 0 and 86,400,000 milliseconds.',
            );
        }

        return NativeModules::call(
            'timers',
            'after',
            ['milliseconds' => $milliseconds],
            static function ($result) use ($callback, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $callback();
            },
        );
    }
}
