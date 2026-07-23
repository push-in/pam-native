<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use InvalidArgumentException;
use Pam\Native\Element;
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
}
