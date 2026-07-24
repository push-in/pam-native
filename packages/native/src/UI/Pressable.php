<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
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

    public function onPress(Closure $handler): self
    {
        return $this->withEvent(EventKind::Press, $handler);
    }

    public function onLongPress(Closure $handler): self
    {
        return $this->withEvent(EventKind::LongPress, $handler);
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
