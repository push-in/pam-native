<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

/** A reused subtree inside a frame, with the root node emitted for it. */
final readonly class SubtreeReference
{
    public function __construct(
        public EncodedSubtree $subtree,
        public EncodedNode $root,
    ) {
    }
}
