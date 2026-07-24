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

final class SectionList extends Element
{
    /**
     * @param array<array-key, mixed> $sections
     */
    public static function make(array $sections): self
    {
        $validated = [];

        foreach ($sections as $title => $items) {
            if (!is_string($title) || !is_array($items)) {
                throw new InvalidArgumentException('SectionList requires string titles and string item lists.');
            }

            $validated[$title] = [];

            foreach ($items as $item) {
                if (!is_string($item)) {
                    throw new InvalidArgumentException('SectionList items must be strings.');
                }

                $validated[$title][] = $item;
            }
        }

        return (new self(NodeKind::SectionList))->withProperty(
            PropKey::SectionItems,
            new BinaryValue(Wire::stringSections($validated)),
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

    public function onScroll(Closure $handler): self
    {
        return $this->withEvent(EventKind::Scroll, $handler);
    }

    public function onEndReached(Closure $handler, float $threshold = 0.5): self
    {
        return $this
            ->withProperty(
                PropKey::EndReachedThreshold,
                min(1.0, max(0.0, $threshold)),
            )
            ->withEvent(EventKind::EndReached, $handler);
    }
}
