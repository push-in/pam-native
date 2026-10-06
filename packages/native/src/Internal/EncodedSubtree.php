<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;

final readonly class EncodedSubtree
{
    /**
     * @param array<int, EncodedNode> $nodes encoded nodes by id, in frame order
     * @param array<string, Closure> $callbacks
     */
    public function __construct(
        public array $nodes,
        public array $callbacks,
    ) {
    }
}
