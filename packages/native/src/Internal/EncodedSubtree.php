<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;

/**
 * One encoded subtree (the root, a keyed/DOM element or a reusable component
 * element), kept per element so an unchanged subtree is never re-encoded.
 *
 * The subtree is stored as its composition: its own nodes and references to
 * the nested subtrees it reused, in frame (depth-first) order. A frame that
 * reuses a subtree the previous frame already emitted therefore costs O(1)
 * instead of O(nodes): only its root placement can differ.
 */
final class EncodedSubtree
{
    /** @var array<int, EncodedNode>|null encoded nodes by id, in frame order */
    private ?array $flat = null;

    /**
     * @param list<EncodedNode|SubtreeReference> $items own root first
     * @param list<EncodedSubtree> $children directly nested subtrees
     * @param array<string, Closure> $callbacks
     */
    public function __construct(
        public readonly array $items,
        public readonly array $children,
        public readonly int $count,
        public readonly array $callbacks,
    ) {
    }

    public function root(): EncodedNode
    {
        $root = $this->items[0];
        \assert($root instanceof EncodedNode);

        return $root;
    }

    /** @return array<int, EncodedNode> */
    public function nodes(): array
    {
        if ($this->flat !== null) {
            return $this->flat;
        }
        $flat = [];
        foreach ($this->items as $item) {
            if ($item instanceof EncodedNode) {
                $flat[$item->id] = $item;
                continue;
            }
            $nested = $item->subtree->nodes();
            $root = $item->root;
            if ($nested[$root->id] !== $root) {
                $nested[$root->id] = $root;
            }
            $flat += $nested;
        }

        return $this->flat = $flat;
    }
}
