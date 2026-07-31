<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\GestureComposition;
use Pam\Native\GestureDirection;
use Pam\Native\GestureEvent;
use Pam\Native\GestureType;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class GestureDetector extends Element
{
    public static function make(
        GestureType $type,
        Renderable $content,
    ): self {
        $minimumPointers = match ($type) {
            GestureType::Pinch, GestureType::Rotation => 2,
            default => 1,
        };
        $minimumDistance = match ($type) {
            GestureType::Swipe => 48.0,
            GestureType::Pan => 8.0,
            default => 12.0,
        };
        $minimumDuration = $type === GestureType::LongPress ? 500 : 0;

        return (new self(NodeKind::Pressable))
            ->withChildren([$content])
            ->withProperty(PropKey::GestureType, $type->value)
            ->withProperty(PropKey::GestureEnabled, true)
            ->withProperty(PropKey::GestureMinPointers, $minimumPointers)
            ->withProperty(PropKey::GestureMaxPointers, $minimumPointers)
            ->withProperty(PropKey::GestureDirection, GestureDirection::Any->value)
            ->withProperty(
                PropKey::GestureComposition,
                GestureComposition::Exclusive->value,
            )
            ->withProperty(PropKey::GestureMinDistance, $minimumDistance)
            ->withProperty(PropKey::GestureMinDurationMs, $minimumDuration);
    }

    public function pointers(int $minimum = 1, int $maximum = 1): self
    {
        if ($minimum < 1 || $maximum < $minimum || $maximum > 10) {
            throw new InvalidArgumentException(
                'Gesture pointers must satisfy 1 <= minimum <= maximum <= 10.',
            );
        }

        return $this
            ->withProperty(PropKey::GestureMinPointers, $minimum)
            ->withProperty(PropKey::GestureMaxPointers, $maximum);
    }

    public function direction(GestureDirection $direction): self
    {
        return $this->withProperty(PropKey::GestureDirection, $direction->value);
    }

    public function composition(GestureComposition $composition): self
    {
        return $this->withProperty(
            PropKey::GestureComposition,
            $composition->value,
        );
    }

    public function minimumDistance(float $distance): self
    {
        return $this->withProperty(
            PropKey::GestureMinDistance,
            max(0.0, min(10_000.0, $distance)),
        );
    }

    public function minimumDuration(int $durationMs): self
    {
        return $this->withProperty(
            PropKey::GestureMinDurationMs,
            max(0, min(60_000, $durationMs)),
        );
    }

    public function gestureEnabled(bool $enabled): self
    {
        return $this->withProperty(PropKey::GestureEnabled, $enabled);
    }

    public function nativeTransform(
        bool $enabled = true,
        float $minimumScale = 1.0,
        float $maximumScale = 4.0,
        int $resetKey = 0,
    ): self {
        return $this
            ->withProperty(PropKey::GestureNativeTransform, $enabled)
            ->withProperty(
                PropKey::GestureNativeMinScale,
                max(0.01, min(100.0, $minimumScale)),
            )
            ->withProperty(
                PropKey::GestureNativeMaxScale,
                max($minimumScale, min(100.0, $maximumScale)),
            )
            ->withProperty(PropKey::GestureNativeResetKey, max(0, $resetKey));
    }

    /** @param Closure(GestureEvent): void $handler */
    public function onBegin(Closure $handler): self
    {
        return $this->withGestureEvent(EventKind::GestureBegin, $handler);
    }

    /** @param Closure(GestureEvent): void $handler */
    public function onUpdate(Closure $handler): self
    {
        return $this->withGestureEvent(EventKind::GestureUpdate, $handler);
    }

    /** @param Closure(GestureEvent): void $handler */
    public function onEnd(Closure $handler): self
    {
        return $this->withGestureEvent(EventKind::GestureEnd, $handler);
    }

    /** @param Closure(GestureEvent): void $handler */
    public function onCancel(Closure $handler): self
    {
        return $this->withGestureEvent(EventKind::GestureCancel, $handler);
    }

    private function withGestureEvent(EventKind $kind, Closure $handler): self
    {
        return $this->withEvent(
            $kind,
            static fn (string $payload): mixed => $handler(
                GestureEvent::fromPayload($payload),
            ),
        );
    }
}
