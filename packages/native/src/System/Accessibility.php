<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use InvalidArgumentException;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;

/** Screen-reader integration (TalkBack / VoiceOver) and the application text scale. */
final class Accessibility
{
    private function __construct()
    {
    }

    /**
     * Speaks a polite announcement through the active screen reader, e.g. after
     * a message was sent or an upload finished. No-op when none is running.
     *
     * @param null|Closure(bool): void $callback Receives whether a screen reader received it.
     */
    public static function announce(string $text, ?Closure $callback = null): int
    {
        $text = trim($text);
        if ($text === '' || mb_strlen($text) > 4_096) {
            throw new InvalidArgumentException('Announcements must contain between 1 and 4096 characters.');
        }

        return NativeModules::call('accessibility', 'announce', ['text' => $text], static function ($result) use ($callback): void {
            if ($result->status === ModuleResultStatus::Failure) {
                $callback?->__invoke(false);

                return;
            }
            $callback?->__invoke((bool) (Wire::decodeMap($result->payload)['delivered'] ?? false));
        });
    }

    /** @param Closure(bool): void $callback Receives whether a screen reader is active. */
    public static function screenReaderEnabled(Closure $callback): int
    {
        return NativeModules::call('accessibility', 'status', [], static function ($result) use ($callback): void {
            if ($result->status === ModuleResultStatus::Failure) {
                $callback(false);

                return;
            }
            $values = Wire::decodeMap($result->payload);
            $callback((bool) ($values['touchExploration'] ?? $values['enabled'] ?? false));
        });
    }

    public const MIN_TEXT_SCALE = 0.5;
    public const MAX_TEXT_SCALE = 3.0;

    /** @param Closure(TextScale): void $callback */
    public static function textScale(Closure $callback): int
    {
        return NativeModules::call('accessibility', 'textScale', [], static function ($result) use ($callback): void {
            $callback(self::textScaleFrom($result));
        });
    }

    /**
     * Stores the app text multiplier (the in-app "text size" setting) for the
     * next launch. Text then renders at `multiplier * min(system scale,
     * maxSystemScale)`; 0 leaves the operating-system scale uncapped.
     *
     * @param null|Closure(?TextScale): void $callback null when it could not be stored
     */
    public static function setTextScale(float $multiplier, float $maxSystemScale = 0.0, ?Closure $callback = null): int
    {
        if (!is_finite($multiplier) || $multiplier < self::MIN_TEXT_SCALE || $multiplier > self::MAX_TEXT_SCALE) {
            throw new InvalidArgumentException('The text scale multiplier must be between 0.5 and 3.');
        }
        if (!is_finite($maxSystemScale) || ($maxSystemScale !== 0.0 && $maxSystemScale < 1.0)) {
            throw new InvalidArgumentException('maxSystemScale must be 0 (uncapped) or at least 1.');
        }

        return NativeModules::call(
            'accessibility',
            'setTextScale',
            ['multiplier' => $multiplier, 'maxSystemScale' => $maxSystemScale],
            static function ($result) use ($callback): void {
                $callback?->__invoke($result->status === ModuleResultStatus::Failure ? null : self::textScaleFrom($result));
            },
        );
    }

    private static function textScaleFrom(mixed $result): TextScale
    {
        if ($result->status === ModuleResultStatus::Failure) {
            return new TextScale();
        }
        $values = Wire::decodeMap($result->payload);
        $float = static fn (string $key, float $default): float => is_int($values[$key] ?? null) || is_float($values[$key] ?? null)
            ? (float) $values[$key]
            : $default;

        return new TextScale(
            multiplier: $float('multiplier', 1.0),
            maxSystemScale: $float('maxSystemScale', 0.0),
            appliedMultiplier: $float('appliedMultiplier', 1.0),
            appliedMaxSystemScale: $float('appliedMaxSystemScale', 0.0),
            systemScale: $float('systemScale', 1.0),
            effectiveScale: $float('effectiveScale', 1.0),
        );
    }
}
