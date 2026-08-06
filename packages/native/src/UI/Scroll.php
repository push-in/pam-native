<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\ScrollKeyboardDismissMode;
use Pam\Native\ScrollOverScrollMode;
use Pam\Native\ScrollTargetAlignment;

final class Scroll extends Element
{
    public static function make(Renderable $child): self
    {
        return (new self(NodeKind::Scroll))->withChildren([$child]);
    }

    public function scrollEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::ScrollEnabled, $enabled);
    }

    public function showsIndicator(bool $visible = true): self
    {
        return $this->withProperty(PropKey::ShowsScrollIndicator, $visible);
    }

    public function horizontal(bool $horizontal = true): self
    {
        return $this->withProperty(PropKey::ScrollHorizontal, $horizontal);
    }

    public function contentOffset(float $x = 0.0, float $y = 0.0): self
    {
        return $this
            ->withProperty(PropKey::ScrollContentOffsetX, max(0.0, $x))
            ->withProperty(PropKey::ScrollContentOffsetY, max(0.0, $y));
    }

    public function anchorToEnd(bool $anchor = true): self
    {
        return $this->withProperty(PropKey::ScrollAnchorToEnd, $anchor);
    }

    public function maintainVisibleContentPosition(bool $maintain = true): self
    {
        return $this->withProperty(
            PropKey::ScrollMaintainVisibleContentPosition,
            $maintain,
        );
    }

    public function autoScrollToEndThreshold(float $threshold): self
    {
        return $this->withProperty(
            PropKey::ScrollAutoScrollToEndThreshold,
            max(0.0, $threshold),
        );
    }

    public function scrollRequest(
        int $request,
        string $targetTestId = '',
        float $targetOffset = -1.0,
        ScrollTargetAlignment $targetAlignment = ScrollTargetAlignment::Start,
    ): self
    {
        return $this
            ->withProperty(PropKey::ScrollTargetTestId, $targetTestId)
            ->withProperty(PropKey::ScrollTargetOffset, $targetOffset)
            ->withProperty(PropKey::ScrollTargetAlignment, $targetAlignment->value)
            ->withProperty(PropKey::ScrollRequest, max(0, $request));
    }

    public function fillViewport(bool $fill = true): self
    {
        return $this->withProperty(PropKey::ScrollFillViewport, $fill);
    }

    public function overScrollMode(ScrollOverScrollMode $mode): self
    {
        return $this->withProperty(PropKey::ScrollOverScrollMode, $mode->value);
    }

    public function nestedScrollEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::ScrollNestedEnabled, $enabled);
    }

    public function fadingEdgeLength(float $length): self
    {
        return $this->withProperty(
            PropKey::ScrollFadingEdgeLength,
            max(0.0, $length),
        );
    }

    public function persistentScrollbar(bool $persistent = true): self
    {
        return $this->withProperty(
            PropKey::ScrollPersistentScrollbar,
            $persistent,
        );
    }

    public function pagingEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::ScrollPagingEnabled, $enabled);
    }

    public function snapToInterval(float $interval): self
    {
        return $this->withProperty(
            PropKey::ScrollSnapInterval,
            max(0.0, $interval),
        );
    }

    public function decelerationRate(float $rate): self
    {
        return $this->withProperty(
            PropKey::ScrollDecelerationRate,
            min(1.0, max(0.0, $rate)),
        );
    }

    public function keyboardDismissMode(ScrollKeyboardDismissMode $mode): self
    {
        return $this->withProperty(
            PropKey::ScrollKeyboardDismissMode,
            $mode->value,
        );
    }

    public function onScroll(Closure $handler): self
    {
        return $this->withEvent(EventKind::Scroll, $handler);
    }
}
