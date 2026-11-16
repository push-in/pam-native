<?php

declare(strict_types=1);

namespace Pam\Native\Navigation;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class NavigationHost extends Element
{
    public static function make(
        NavigationOperation $operation,
        NavigationTransition $transition,
        int $durationMs,
        int $revision,
        Renderable ...$screens,
    ): self {
        return (new self(NodeKind::NavigationHost))
            ->withChildren($screens)
            ->withProperty(PropKey::FlexGrow, 1.0)
            ->withProperty(PropKey::NavigationOperation, $operation->value)
            ->withProperty(PropKey::NavigationTransition, $transition->value)
            ->withProperty(PropKey::NavigationDurationMs, max(0, min(2_000, $durationMs)))
            ->withProperty(PropKey::NavigationRevision, $revision)
            ->withProperty(PropKey::NavigationGestureEnabled, true)
            ->withProperty(PropKey::NavigationGestureEdgeWidth, 24.0)
            ->withProperty(PropKey::NavigationGestureThreshold, 0.35);
    }

    /**
     * The screen mounted ahead by Navigator::prewarm() (the host's first
     * child) and the motion its push will use. A pressed Pressable whose
     * navigation intent matches `$intent` starts that push natively on the
     * frame after the tap; the commit of the push then adopts the running
     * transition instead of starting it. Sent only to renderers that
     * implement it (older ones keep waiting for the commit).
     */
    public function warmRoute(string $intent, NavigationTransition $transition, int $durationMs): self
    {
        $contract = \Pam\Native\RendererContract::current();
        if ($intent === '' || !$contract->supportsProperty(PropKey::NavigationWarmDurationMs->value)) {
            return $this;
        }

        return $this
            ->withProperty(PropKey::NavigationWarmIntent, $intent)
            ->withProperty(PropKey::NavigationWarmTransition, $transition->value)
            ->withProperty(PropKey::NavigationWarmDurationMs, max(0, min(2_000, $durationMs)));
    }

    public function gestureNavigation(
        bool $enabled = true,
        float $edgeWidth = 24.0,
        float $threshold = 0.35,
        NavigationGestureDirection $direction = NavigationGestureDirection::Horizontal,
        bool $fullScreen = false,
    ): self {
        return $this
            ->withProperty(PropKey::NavigationGestureEnabled, $enabled)
            ->withProperty(
                PropKey::NavigationGestureEdgeWidth,
                max(8.0, min(96.0, $edgeWidth)),
            )
            ->withProperty(
                PropKey::NavigationGestureThreshold,
                max(0.1, min(0.9, $threshold)),
            )
            ->withProperty(PropKey::NavigationGestureDirection, $direction->value)
            ->withProperty(PropKey::NavigationFullScreenGestureEnabled, $fullScreen);
    }

    public function onGesturePop(Closure $handler): self
    {
        return $this->withEvent(EventKind::NavigationGesturePop, $handler);
    }

    public function onTransitionEnd(Closure $handler): self
    {
        return $this->withEvent(EventKind::AnimationComplete, $handler);
    }

    public function onGestureStart(Closure $handler): self
    {
        return $this->withEvent(EventKind::GestureBegin, $handler);
    }

    public function onGestureEnd(Closure $handler): self
    {
        return $this->withEvent(EventKind::GestureEnd, $handler);
    }

    public function onGestureCancel(Closure $handler): self
    {
        return $this->withEvent(EventKind::GestureCancel, $handler);
    }

    public function screenBehavior(
        NavigationOrientation $orientation,
        bool $autoHideHomeIndicator = false,
    ): self {
        return $this
            ->withProperty(PropKey::NavigationOrientation, $orientation->value)
            ->withProperty(PropKey::NavigationAutoHideHomeIndicator, $autoHideHomeIndicator);
    }

    /** Sends the already-resolved active route options to native controllers. */
    public function screenOptions(ScreenOptions $options): self
    {
        $host = $this
            ->withProperty(PropKey::NavigationTitle, $options->title ?? '')
            ->withProperty(PropKey::NavigationHeaderShown, $options->headerShown)
            ->withProperty(PropKey::NavigationHeaderTransparent, $options->headerTransparent)
            ->withProperty(PropKey::NavigationHeaderShadowVisible, $options->headerShadowVisible)
            ->withProperty(PropKey::NavigationHeaderLargeTitleEnabled, $options->headerLargeTitleEnabled)
            ->withProperty(PropKey::NavigationHeaderSearchEnabled, $options->headerSearchEnabled)
            ->withProperty(PropKey::NavigationHeaderSearchPlaceholder, $options->headerSearchPlaceholder)
            ->withProperty(PropKey::NavigationPresentation, $options->presentation->value)
            ->withProperty(PropKey::NavigationGestureDirection, $options->gestureDirection->value)
            ->withProperty(PropKey::NavigationFullScreenGestureEnabled, $options->fullScreenGestureEnabled)
            ->withProperty(PropKey::NavigationFreezeOnBlur, $options->freezeOnBlur)
            ->withProperty(PropKey::NavigationSheetDetents, implode(',', $options->sheetAllowedDetents ?? [1.0]))
            ->withProperty(PropKey::NavigationSheetInitialDetentIndex, $options->sheetInitialDetentIndex)
            ->withProperty(PropKey::NavigationSheetGrabberVisible, $options->sheetGrabberVisible)
            ->withProperty(PropKey::NavigationSheetCornerRadius, $options->sheetCornerRadius ?? 0.0)
            ->withProperty(PropKey::NavigationSheetExpandsWhenScrolledToEdge, $options->sheetExpandsWhenScrolledToEdge);

        if ($options->headerBackgroundColor !== null) {
            $host = $host->withProperty(PropKey::NavigationHeaderBackgroundColor, $options->headerBackgroundColor);
        }
        if ($options->headerTintColor !== null) {
            $host = $host->withProperty(PropKey::NavigationHeaderTintColor, $options->headerTintColor);
        }
        if ($options->onHeaderSearchChange !== null) {
            $host = $host->withEvent(EventKind::Change, $options->onHeaderSearchChange);
        }

        return $host;
    }
}
