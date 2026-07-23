<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Pam\Native\Element;

final readonly class SubtreeCacheCandidate
{
    public function __construct(
        public Element $element,
        public string $path,
        public int $start,
        public int $length,
    ) {
    }
}
