<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

use function is_float;
use function is_int;

final readonly class GestureEvent
{
    public function __construct(
        public GestureType $type,
        public GestureState $state,
        public float $x,
        public float $y,
        public float $pageX,
        public float $pageY,
        public float $translationX,
        public float $translationY,
        public float $velocityX,
        public float $velocityY,
        public float $scale,
        public float $rotation,
        public int $pointerCount,
        public int $timestamp,
        public int $snapIndex = -1,
        public bool $thresholdReached = false,
        /** Applied native transform, when gestureNativeTransform is enabled. Lengths are dp. */
        public ?float $nativeScale = null,
        public ?float $nativeTranslationX = null,
        public ?float $nativeTranslationY = null,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = Wire::decodeMap($payload);

        return new self(
            type: GestureType::from(self::integer($values['type'] ?? null, 1)),
            state: GestureState::from(self::integer($values['state'] ?? null, 1)),
            x: self::number($values['x'] ?? null),
            y: self::number($values['y'] ?? null),
            pageX: self::number($values['pageX'] ?? null),
            pageY: self::number($values['pageY'] ?? null),
            translationX: self::number($values['translationX'] ?? null),
            translationY: self::number($values['translationY'] ?? null),
            velocityX: self::number($values['velocityX'] ?? null),
            velocityY: self::number($values['velocityY'] ?? null),
            scale: self::number($values['scale'] ?? null, 1.0),
            rotation: self::number($values['rotation'] ?? null),
            pointerCount: self::integer($values['pointerCount'] ?? null, 1),
            timestamp: self::integer($values['timestamp'] ?? null),
            snapIndex: self::integer($values['snapIndex'] ?? null, -1),
            thresholdReached: ($values['thresholdReached'] ?? false) === true,
            nativeScale: self::optionalNumber($values['nativeScale'] ?? null),
            nativeTranslationX: self::optionalNumber($values['nativeTranslationX'] ?? null),
            nativeTranslationY: self::optionalNumber($values['nativeTranslationY'] ?? null),
        );
    }

    private static function number(mixed $value, float $fallback = 0.0): float
    {
        return is_int($value) || is_float($value) ? (float) $value : $fallback;
    }

    private static function optionalNumber(mixed $value): ?float
    {
        return (is_int($value) || is_float($value)) && is_finite((float) $value) ? (float) $value : null;
    }

    private static function integer(mixed $value, int $fallback = 0): int
    {
        return is_int($value) ? $value : $fallback;
    }
}
