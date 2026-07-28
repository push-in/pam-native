<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;

final class Clipboard
{
    private const MAX_TEXT_BYTES = 1_048_576;

    private function __construct()
    {
    }

    /** @param Closure(bool): void|null $completed */
    public static function setText(string $text, ?Closure $completed = null): int
    {
        if (strlen($text) > self::MAX_TEXT_BYTES) {
            throw new \InvalidArgumentException('Clipboard text cannot exceed one megabyte.');
        }

        return Runtime::callNative(
            NativeOperation::ClipboardSetText,
            Wire::map(['text' => $text]),
            static function (ModuleResultStatus $status) use ($completed): void {
                $completed?->__invoke($status === ModuleResultStatus::Success);
            },
        );
    }

    /** @param Closure(?string): void $completed */
    public static function getText(Closure $completed): int
    {
        return Runtime::callNative(
            NativeOperation::ClipboardGetText,
            Wire::map([]),
            static function (ModuleResultStatus $status, string $payload) use ($completed): void {
                if ($status !== ModuleResultStatus::Success) {
                    $completed(null);

                    return;
                }

                $value = Wire::decodeMap($payload)['text'] ?? null;
                $completed(is_string($value) ? $value : null);
            },
        );
    }

    /** @param Closure(bool): void $completed */
    public static function hasText(Closure $completed): int
    {
        return Runtime::callNative(
            NativeOperation::ClipboardHasText,
            Wire::map([]),
            static function (ModuleResultStatus $status, string $payload) use ($completed): void {
                if ($status !== ModuleResultStatus::Success) {
                    $completed(false);

                    return;
                }

                $completed((Wire::decodeMap($payload)['hasText'] ?? false) === true);
            },
        );
    }
}
