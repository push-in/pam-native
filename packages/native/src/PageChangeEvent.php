<?php

declare(strict_types=1);

namespace Pam\Native;

use Pam\Native\Internal\Wire;

use function is_int;

/**
 * `on:pageChange` payload: the page now current along the scroll axis
 * (paging extent, snap interval or, for lists, the snapped item) and the
 * page it replaced. Sent once per change while dragging, never per frame.
 */
final readonly class PageChangeEvent
{
    public function __construct(
        public int $page,
        public int $previousPage,
    ) {
    }

    public static function fromPayload(string $payload): self
    {
        $values = $payload === '' ? [] : Wire::decodeMap($payload);
        $page = $values['page'] ?? null;
        $previous = $values['previousPage'] ?? null;

        return new self(
            is_int($page) ? $page : 0,
            is_int($previous) ? $previous : 0,
        );
    }
}
