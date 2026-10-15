<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\UI\Concerns\HasRefreshControl;
use Pam\Native\UI\Concerns\HasScrollPhaseEvents;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\Internal\Wire;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

use function is_bool;
use function is_string;

final class FlatList extends Element
{
    use HasRefreshControl;
    use HasScrollPhaseEvents;

    /** @param array<array-key, mixed> $items */
    public static function make(array $items): self
    {
        $values = [];

        foreach ($items as $item) {
            if (!is_string($item)) {
                throw new InvalidArgumentException('FlatList items must be strings.');
            }

            $values[] = $item;
        }

        return (new self(NodeKind::List))->withProperty(
            PropKey::Items,
            new BinaryValue(Wire::stringList($values)),
        );
    }

    public function rowHeight(float $height): self
    {
        return $this->withProperty(PropKey::ListRowHeight, max(1.0, $height));
    }

    public function prefetch(int $items): self
    {
        return $this->withProperty(
            PropKey::ListPrefetch,
            min(32, max(1, $items)),
        );
    }

    /**
     * Scrolls to [targetOffset] (or the `testID` [targetTestId]) each time
     * [request] changes. A fling in progress is stopped first, so the list
     * lands exactly on the target; [animated] scrolls smoothly to it.
     */
    public function scrollRequest(
        int $request,
        string $targetTestId = '',
        float $targetOffset = -1.0,
        \Pam\Native\ScrollTargetAlignment $targetAlignment = \Pam\Native\ScrollTargetAlignment::Start,
        bool $animated = false,
    ): self {
        return $this
            ->withProperty(PropKey::ScrollTargetTestId, $targetTestId)
            ->withProperty(PropKey::ScrollTargetOffset, $targetOffset)
            ->withProperty(PropKey::ScrollTargetAlignment, $targetAlignment->value)
            ->withProperty(PropKey::ScrollAnimated, $animated)
            ->withProperty(PropKey::ScrollRequest, max(0, $request));
    }

    /**
     * React Native `keyboardShouldPersistTaps`: what a tap inside this
     * scroll view does while the soft keyboard is visible. `true` is
     * `Always`, `false` is `Never`. Without it, taps never dismiss the
     * keyboard (PAM's historical behaviour).
     */
    public function keyboardShouldPersistTaps(\Pam\Native\KeyboardPersistTaps|bool $mode): self
    {
        if (is_bool($mode)) {
            $mode = \Pam\Native\KeyboardPersistTaps::fromBool($mode);
        }

        return $this->withProperty(PropKey::ScrollKeyboardPersistTaps, $mode->value);
    }

    public function horizontal(bool $horizontal = true): self
    {
        return $this->withProperty(PropKey::ListHorizontal, $horizontal);
    }

    public function columns(int $columns): self
    {
        return $this->withProperty(PropKey::ListNumColumns, max(1, $columns));
    }

    public function inverted(bool $inverted = true): self
    {
        return $this->withProperty(PropKey::ListInverted, $inverted);
    }

    /**
     * Whether a list resting at its end (opened there with
     * `initialScrollIndex` or scrolled there) keeps the end visible when its
     * viewport resizes. Default true; pass false for lists in sheets/modals
     * that must keep their offset whatever the container animates.
     */
    public function followEndOnResize(bool $follow = true): self
    {
        return $this->withProperty(PropKey::ListFollowEndOnResize, $follow);
    }

    public function initialScrollIndex(int $index): self
    {
        return $this->withProperty(
            PropKey::ListInitialScrollIndex,
            max(0, $index),
        );
    }

    public function removeClippedSubviews(bool $remove = true): self
    {
        return $this->withProperty(PropKey::ListRemoveClippedSubviews, $remove);
    }

    public function scrollEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::ScrollEnabled, $enabled);
    }

    public function showsIndicator(bool $visible = true): self
    {
        return $this->withProperty(PropKey::ShowsScrollIndicator, $visible);
    }

    /** RN `pagingEnabled` for lists: one item per page (Reels), snapped natively. */
    public function pagingEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::ScrollPagingEnabled, $enabled);
    }

    public function onScroll(Closure $handler): self
    {
        return $this->withEvent(EventKind::Scroll, $handler);
    }

    public function onEndReached(Closure $handler, float $threshold = 0.5): self
    {
        return $this
            ->withProperty(PropKey::EndReachedThreshold, min(1.0, max(0.0, $threshold)))
            ->withEvent(EventKind::EndReached, $handler);
    }
}
