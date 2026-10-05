<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Animation\TapEffect;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PressEvent;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class Pressable extends Element
{
    public static function make(Renderable ...$children): self
    {
        return (new self(NodeKind::Pressable))->withChildren($children);
    }

    /**
     * Handlers typed `PressEvent $event` receive the touch location
     * (locationX/Y as `x`/`y`, pageX/pageY); others keep the empty payload.
     */
    public function onPress(Closure $handler): self
    {
        return $this->withEvent(EventKind::Press, self::pressHandler($handler));
    }

    /** Fires after `delayLongPress` while held; pair with `onPressOut` for hold-to-record. */
    public function onLongPress(Closure $handler): self
    {
        return $this->withEvent(EventKind::LongPress, self::pressHandler($handler));
    }

    /**
     * Second tap within `doubleTapDelay` near the first. When present, the
     * single `onPress` is deferred by that delay and cancelled by a double tap
     * (the RN ReelPage pattern), all on the UI thread.
     */
    public function onDoubleTap(Closure $handler): self
    {
        return $this->withPressEvent(EventKind::DoubleTap, $handler);
    }

    public function doubleTapDelay(int $milliseconds): self
    {
        return $this->withProperty(PropKey::PressDoubleTapDelayMs, max(80, min(1_000, $milliseconds)));
    }

    /** Native animation centred on the double-tap point (Reels heart burst). */
    public function tapEffect(TapEffect $effect): self
    {
        return $this->withProperty(PropKey::PressTapEffect, $effect->encode());
    }

    public function onPressIn(Closure $handler): self
    {
        return $this->withPressEvent(EventKind::PressIn, $handler);
    }

    public function onPressOut(Closure $handler): self
    {
        return $this->withPressEvent(EventKind::PressOut, $handler);
    }

    public function onPressMove(Closure $handler): self
    {
        return $this->withPressEvent(EventKind::PressMove, $handler);
    }

    public function ripple(
        int $color,
        bool $borderless = false,
        ?float $radius = null,
        bool $foreground = false,
        float $alpha = 1.0,
    ): self {
        $pressable = $this
            ->withProperty(PropKey::RippleColor, $color)
            ->withProperty(PropKey::RippleBorderless, $borderless)
            ->withProperty(PropKey::RippleForeground, $foreground)
            ->withProperty(PropKey::RippleAlpha, min(1.0, max(0.0, $alpha)));

        return $radius === null
            ? $pressable
            : $pressable->withProperty(PropKey::RippleRadius, max(0.0, $radius));
    }

    public function pressedOpacity(float $opacity): self
    {
        return $this->withProperty(PropKey::PressOpacity, min(1.0, max(0.0, $opacity)));
    }

    public function pressedScale(float $scale): self
    {
        return $this->withProperty(PropKey::PressScale, min(4.0, max(0.01, $scale)));
    }

    public function hitSlop(float $amount): self
    {
        return $this->hitSlopEdges($amount, $amount, $amount, $amount);
    }

    public function hitSlopEdges(
        float $left,
        float $top,
        float $right,
        float $bottom,
    ): self {
        return $this
            ->withProperty(PropKey::HitSlopLeft, max(0.0, $left))
            ->withProperty(PropKey::HitSlopTop, max(0.0, $top))
            ->withProperty(PropKey::HitSlopRight, max(0.0, $right))
            ->withProperty(PropKey::HitSlopBottom, max(0.0, $bottom));
    }

    public function pressRetentionOffset(float $amount): self
    {
        return $this->pressRetentionEdges($amount, $amount, $amount, $amount);
    }

    public function pressRetentionEdges(
        float $left,
        float $top,
        float $right,
        float $bottom,
    ): self {
        return $this
            ->withProperty(PropKey::PressRetentionLeft, max(0.0, $left))
            ->withProperty(PropKey::PressRetentionTop, max(0.0, $top))
            ->withProperty(PropKey::PressRetentionRight, max(0.0, $right))
            ->withProperty(PropKey::PressRetentionBottom, max(0.0, $bottom));
    }

    public function delayLongPress(int $milliseconds): self
    {
        return $this->withProperty(
            PropKey::PressDelayLongMs,
            min(60_000, max(0, $milliseconds)),
        );
    }

    public function delayPressIn(int $milliseconds): self
    {
        return $this->withProperty(
            PropKey::PressDelayInMs,
            min(60_000, max(0, $milliseconds)),
        );
    }

    public function delayPressOut(int $milliseconds): self
    {
        return $this->withProperty(
            PropKey::PressDelayOutMs,
            min(60_000, max(0, $milliseconds)),
        );
    }

    public function androidDisableSound(bool $disabled = true): self
    {
        return $this->withProperty(PropKey::PressAndroidDisableSound, $disabled);
    }

    private static function pressHandler(Closure $handler): Closure
    {
        $parameter = (new \ReflectionFunction($handler))->getParameters()[0] ?? null;
        $type = $parameter?->getType();
        $typed = $type instanceof \ReflectionNamedType && $type->getName() === PressEvent::class;

        return $typed
            ? static fn (string $payload = ''): mixed => $handler(PressEvent::fromPayload($payload))
            : static fn (mixed $payload = ''): mixed => $handler(is_string($payload) ? '' : $payload);
    }

    private function withPressEvent(EventKind $kind, Closure $handler): self
    {
        return $this->withEvent(
            $kind,
            static function (string $payload) use ($handler): void {
                $handler(PressEvent::fromPayload($payload));
            },
        );
    }
}
