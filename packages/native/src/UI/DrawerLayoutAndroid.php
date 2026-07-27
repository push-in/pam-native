<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\Navigation\DrawerKeyboardDismissMode;
use Pam\Native\Navigation\DrawerPosition;
use Pam\Native\Navigation\DrawerStatusBarAnimation;
use Pam\Native\Navigation\DrawerType;

final class DrawerLayoutAndroid extends Element
{
    public static function make(Renderable $content, Renderable $drawer): self
    {
        return (new self(NodeKind::DrawerLayout))->withChildren([$content, $drawer]);
    }

    public function open(bool $open = true): self
    {
        return $this->withProperty(PropKey::DrawerOpen, $open);
    }

    public function presentation(
        DrawerType $type,
        DrawerPosition $position = DrawerPosition::Automatic,
    ): self {
        return $this
            ->withProperty(PropKey::DrawerType, $type->value)
            ->withProperty(PropKey::DrawerPosition, $position->value);
    }

    public function width(float $width): self
    {
        return $this->withProperty(
            PropKey::DrawerWidth,
            max(200.0, min(640.0, $width)),
        );
    }

    public function overlayColor(int $color): self
    {
        return $this->withProperty(PropKey::DrawerOverlayColor, $color);
    }

    public function gestures(
        bool $enabled = true,
        float $edgeWidth = 32.0,
        float $minimumDistance = 56.0,
        DrawerKeyboardDismissMode $keyboard =
            DrawerKeyboardDismissMode::OnDrag,
    ): self {
        return $this
            ->withProperty(PropKey::DrawerSwipeEnabled, $enabled)
            ->withProperty(
                PropKey::DrawerSwipeEdgeWidth,
                max(0.0, min(256.0, $edgeWidth)),
            )
            ->withProperty(
                PropKey::DrawerSwipeMinDistance,
                max(1.0, min(512.0, $minimumDistance)),
            )
            ->withProperty(PropKey::DrawerKeyboardDismissMode, $keyboard->value);
    }

    public function statusBar(
        bool $hideOnOpen,
        DrawerStatusBarAnimation $animation =
            DrawerStatusBarAnimation::Slide,
    ): self {
        return $this
            ->withProperty(PropKey::DrawerHideStatusBarOnOpen, $hideOnOpen)
            ->withProperty(PropKey::DrawerStatusBarAnimation, $animation->value);
    }

    public function permanentBreakpoint(float $width): self
    {
        return $this->withProperty(
            PropKey::DrawerPermanentBreakpoint,
            max(0.0, $width),
        );
    }

    public function onOpen(Closure $handler): self
    {
        return $this->withEvent(EventKind::DrawerOpen, $handler);
    }

    public function onClose(Closure $handler): self
    {
        return $this->withEvent(EventKind::DrawerClose, $handler);
    }
}
