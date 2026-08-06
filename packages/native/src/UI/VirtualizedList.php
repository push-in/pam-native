<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\Internal\Wire;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\ScrollTargetAlignment;
use Pam\Native\Renderable;

final class VirtualizedList extends Element
{
    /**
     * Creates a lazily mounted native list from arbitrary PAM components.
     *
     * Assign stable keys to stateful or reorderable children.
     */
    public static function make(Renderable|array ...$items): self
    {
        if (count($items) === 1 && is_array($items[0])) {
            $strings = [];
            foreach ($items[0] as $item) {
                if (!is_string($item)) {
                    throw new InvalidArgumentException(
                        'Legacy VirtualizedList array items must be strings; '
                        .'pass renderable elements as variadic arguments for rich cells.',
                    );
                }
                $strings[] = $item;
            }

            return (new self(NodeKind::List))->withProperty(
                PropKey::Items,
                new BinaryValue(Wire::stringList($strings)),
            );
        }

        /** @var list<Renderable> $items */
        return (new self(NodeKind::VirtualList))->withChildren($items);
    }

    public function rowHeight(float $height): self
    {
        return $this->withProperty(PropKey::ListRowHeight, max(1.0, $height));
    }

    /**
     * Sets the fallback and prefetch estimate for cells without an authored
     * main-axis size. Explicit cell heights or widths remain authoritative.
     */
    public function estimatedRowHeight(float $height): self
    {
        return $this->rowHeight($height);
    }

    public function prefetch(int $items): self
    {
        return $this->withProperty(PropKey::ListPrefetch, min(32, max(1, $items)));
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

    public function initialScrollIndex(int $index): self
    {
        return $this->withProperty(PropKey::ListInitialScrollIndex, max(0, $index));
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

    public function scrollRequest(
        int $request,
        string $targetTestId = '',
        float $targetOffset = -1.0,
        ScrollTargetAlignment $targetAlignment = ScrollTargetAlignment::Start,
    ): self {
        return $this
            ->withProperty(PropKey::ScrollTargetTestId, $targetTestId)
            ->withProperty(PropKey::ScrollTargetOffset, $targetOffset)
            ->withProperty(PropKey::ScrollTargetAlignment, $targetAlignment->value)
            ->withProperty(PropKey::ScrollRequest, max(0, $request));
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
