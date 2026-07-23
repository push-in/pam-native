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

final class VirtualizedList extends Element
{
    /** @param array<array-key, mixed> $items */
    public static function make(array $items): self
    {
        $values = [];

        foreach ($items as $item) {
            if (!is_string($item)) {
                throw new InvalidArgumentException('VirtualizedList items must be strings.');
            }

            $values[] = $item;
        }

        return (new self(NodeKind::List))->withProperty(
            PropKey::Items,
            new BinaryValue(Wire::stringList($values)),
        );
    }

    public function onEndReached(Closure $handler): self
    {
        return $this->withEvent(EventKind::EndReached, $handler);
    }
}
