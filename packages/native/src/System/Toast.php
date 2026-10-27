<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\NativeOperation;
use Pam\Native\UI\Icon;

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
     *
     * The card is styleable to match an app's own toast host: `borderColor`
     * and `borderWidth` (dp, 0 = none), `cornerRadius`, `accentWidth` (the
     * left strip, 0 = none), `elevation`, paddings, `minHeight`,
     * `titleLines` (a bold title wrapping on 2 lines), `fitContent` (the card
     * hugs its text up to the screen width minus 16 dp per side instead of a
     * fixed 340 dp card) and a leading `icon`: a packaged `asset://….png`
     * image or a glyph name of the `iconFont` icon font (see `UI\Icon`),
     * tinted with `iconColor` (default: the title color).
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
        ?int $borderColor = null,
        float $borderWidth = 0.0,
        float $cornerRadius = 6.0,
        float $accentWidth = 5.0,
        float $elevation = 2.0,
        float $paddingHorizontal = 15.0,
        float $paddingVertical = 10.0,
        float $minHeight = 60.0,
        int $titleLines = 1,
        bool $fitContent = false,
        ?string $icon = null,
        string $iconFont = 'Ionicons',
        ?int $iconColor = null,
        float $iconSize = 18.0,
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
        $values += self::cardStyle(
            $borderColor,
            $borderWidth,
            $cornerRadius,
            $accentWidth,
            $elevation,
            $paddingHorizontal,
            $paddingVertical,
            $minHeight,
            $titleLines,
            $fitContent,
        );
        $values += self::icon($icon, $iconFont, $iconColor, $iconSize);

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

    /** @return array<string, int|float|bool> */
    private static function cardStyle(
        ?int $borderColor,
        float $borderWidth,
        float $cornerRadius,
        float $accentWidth,
        float $elevation,
        float $paddingHorizontal,
        float $paddingVertical,
        float $minHeight,
        int $titleLines,
        bool $fitContent,
    ): array {
        $style = [
            'cornerRadius' => max(0.0, min(64.0, $cornerRadius)),
            'accentWidth' => max(0.0, min(32.0, $accentWidth)),
            'elevation' => max(0.0, min(24.0, $elevation)),
            'paddingHorizontal' => max(0.0, min(64.0, $paddingHorizontal)),
            'paddingVertical' => max(0.0, min(64.0, $paddingVertical)),
            'minHeight' => max(0.0, min(200.0, $minHeight)),
            'titleLines' => max(1, min(4, $titleLines)),
            'fitContent' => $fitContent,
        ];
        if ($borderColor !== null && $borderWidth > 0.0) {
            $style['borderColor'] = $borderColor;
            $style['borderWidth'] = min(8.0, $borderWidth);
        }

        return $style;
    }

    /** @return array<string, int|float|string> */
    private static function icon(?string $icon, string $iconFont, ?int $iconColor, float $iconSize): array
    {
        if ($icon === null || $icon === '') {
            return [];
        }
        $values = ['iconSize' => max(1.0, min(64.0, $iconSize))];
        if ($iconColor !== null) {
            $values['iconColor'] = $iconColor;
        }
        if (preg_match('/^asset:\/\/[A-Za-z0-9_.\/-]+\.png$/Di', $icon) === 1 && !str_contains($icon, '..')) {
            return $values + ['iconSource' => $icon];
        }
        try {
            $glyph = Icon::glyph($icon, $iconFont);
        } catch (\RuntimeException) {
            // A missing icon font draws the toast without its icon.
            $glyph = null;
        }
        if ($glyph === null) {
            return [];
        }

        return $values + ['iconGlyph' => $glyph['glyph'], 'iconFontFamily' => $glyph['fontFamily']];
    }
}
