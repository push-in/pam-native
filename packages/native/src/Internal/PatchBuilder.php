<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use function array_key_exists;

/** Patch operations of one frame against the committed tree, in frame order. */
final class PatchBuilder
{
    /** @var list<string> */
    public array $creates = [];

    /** @var list<string> */
    public array $moves = [];

    /** @var list<string> */
    public array $updates = [];

    /** @var array<int, EncodedNode> nodes that replace the committed ones */
    public array $changed = [];

    /** @param array<int, EncodedNode> $previous the committed tree */
    public function __construct(private readonly array $previous)
    {
    }

    public function node(EncodedNode $node): void
    {
        $id = $node->id;
        $previous = $this->previous[$id] ?? null;

        if ($previous === $node) {
            return;
        }
        $this->changed[$id] = $node;

        if ($previous === null) {
            $this->creates[] = "\x01".$node->bytes();

            return;
        }

        if (!$node->hasSameTopology($previous)) {
            $this->moves[] = "\x04".pack('PPV', $id, $node->parent, $node->index);
        }

        $before = $previous->properties;
        $after = $node->properties;
        if ($before === $after) {
            return;
        }

        $keys = $before + $after;
        ksort($keys, SORT_NUMERIC);

        foreach ($keys as $key => $_) {
            $hadValue = array_key_exists($key, $before);
            $hasValue = array_key_exists($key, $after);
            $previousValue = $hadValue ? $before[$key] : null;
            $nextValue = $hasValue ? $after[$key] : null;

            if ($hadValue === $hasValue && $previousValue === $nextValue) {
                continue;
            }

            $operation = "\x03"
                .pack('Pv', $id, $key)
                .($hasValue ? "\x01" : "\x02");

            if ($nextValue !== null) {
                $operation .= $nextValue;
            }

            $this->updates[] = $operation;
        }
    }
}
