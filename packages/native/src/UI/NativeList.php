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

final class NativeList extends Element
{
    /** @param array<array-key, mixed> $items */
    public static function make(array $items): self
    {
        $validated = [];

        foreach ($items as $item) {
            if (!is_string($item)) {
                throw new InvalidArgumentException('NativeList items must be strings.');
            }

            $validated[] = $item;
        }

        return (new self(NodeKind::List))->withProperty(
            PropKey::Items,
            new BinaryValue(Wire::stringList($validated)),
        );
    }

    public function rowHeight(float $height): self
    {
        return $this->withProperty(PropKey::ListRowHeight, max(1.0, $height));
    }

    public function prefetch(int $rows): self
    {
        return $this->withProperty(PropKey::ListPrefetch, max(1, $rows));
    }

    public function onEndReached(Closure $handler, float $threshold = 0.5): self
    {
        return $this
            ->withProperty(PropKey::EndReachedThreshold, min(1.0, max(0.0, $threshold)))
            ->withEvent(EventKind::EndReached, $handler);
    }
}
