<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\NativeOperation;

final class Toast
{
    private function __construct()
    {
    }

    /**
     * In-app toast with a title and message (react-native-toast-message
     * defaults: white card, 5 dp accent bar by type, 12/10 sp text, 40 dp from
     * the top safe area, 4 s). Colors are ARGB integers; pass `fontFamily` as
     * a packaged `asset://` font or system family.
     */
    public static function message(
        string $title,
        string $message = '',
        ToastType $type = ToastType::Info,
        ToastPosition $position = ToastPosition::Top,
        int $durationMs = 4_000,
        float $offset = 40.0,
        ?int $accentColor = null,
        int $backgroundColor = 0xFFFFFFFF,
        int $titleColor = 0xFF000000,
        int $messageColor = 0xFF979797,
        float $titleSize = 12.0,
        float $messageSize = 10.0,
        ?string $fontFamily = null,
    ): int {
        $values = [
            'title' => $title,
            'message' => $message,
            'bottom' => $position === ToastPosition::Bottom,
            'durationMs' => max(500, min(60_000, $durationMs)),
            'offset' => max(0.0, $offset),
            'accentColor' => $accentColor ?? $type->accentColor(),
            'backgroundColor' => $backgroundColor,
            'titleColor' => $titleColor,
            'messageColor' => $messageColor,
            'titleSize' => max(1.0, $titleSize),
            'messageSize' => max(1.0, $messageSize),
        ];
        if ($fontFamily !== null && $fontFamily !== '') {
            $values['fontFamily'] = $fontFamily;
        }

        return Runtime::callNative(NativeOperation::Toast, Wire::map($values), static function (): void {
        });
    }

    public static function show(string $message, bool $long = false): int
    {
        return Runtime::callNative(
            NativeOperation::Toast,
            Wire::map(['message' => $message, 'long' => $long]),
            static function (): void {
            },
        );
    }
}
