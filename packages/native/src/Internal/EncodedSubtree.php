<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;

final readonly class EncodedSubtree
{
    /**
     * @param list<EncodedNode> $nodes
     * @param array<string, Closure> $callbacks
     */
    public function __construct(
        public array $nodes,
        public array $callbacks,
    ) {
    }
}
