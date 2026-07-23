<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\NativeOperation;

final class Share
{
    private function __construct()
    {
    }

    public static function text(string $text, ?string $title = null, ?Closure $opened = null): int
    {
        return Runtime::callNative(
            NativeOperation::Share,
            Wire::map([
                'text' => $text,
                'title' => $title ?? '',
            ]),
            static function () use ($opened): void {
                $opened?->__invoke();
            },
        );
    }
}
