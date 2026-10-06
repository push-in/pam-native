<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use InvalidArgumentException;
use LogicException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Protocol;
use Pam\Native\PropKey;
use WeakMap;

final class TreeEncoder
{
    private const MAX_NODES = 100_000;

    private const EVENT_PROPERTIES = EventProperties::MAP;

    /** @var array<int, true> */
    private array $ids = [];

    /** @var array<string, int> */
    private array $identityIds = [];

    /** @var array<int, string> */
    private array $idIdentities = [];

    /** @var array<string, Closure> */
    private array $callbacks = [];

    /** @var array<int, EncodedNode> */
    private array $nodes = [];

    /** @var WeakMap<Element, array<string, EncodedSubtree>> */
    private WeakMap $subtreeCache;

    /** @var list<SubtreeCacheCandidate> */
    private array $cacheCandidates = [];

    /** @var array<int, EncodedNode>|null */
    private ?array $previousNodes = null;

    private ?int $previousRoot = null;
    private int $nodeCount = 0;

    /** @var array<string, string> encoded short strings (validated UTF-8) */
    private static array $encodedStrings = [];

    public function __construct()
    {
        $this->subtreeCache = new WeakMap();
    }

    /**
     * Forces the next encoded tree to be a complete frame.
     *
     * This is used to recover synchronization when a native renderer rejects
     * an incremental patch. Stable element identities and subtree caches are
     * preserved, so recovery does not remount PHP components.
     */
    public function forceFullFrame(): void
    {
        $this->previousNodes = null;
        $this->previousRoot = null;
    }

    /**
     * @return array{frame: ?string, callbacks: array<string, Closure>, full: bool}
     */
    public function encode(Element $root): array
    {
        $cachedRoot = ($this->subtreeCache[$root] ?? [])['root'] ?? null;

        if ($this->previousNodes !== null && $cachedRoot !== null) {
            return [
                'frame' => null,
                'callbacks' => $cachedRoot->callbacks,
                'full' => false,
            ];
        }

        $this->ids = [];
        $this->callbacks = [];
        $this->nodes = [];
        $this->cacheCandidates = [];
        $this->nodeCount = 0;
        $rootInfo = $root->__pamEncoding();
        $rootId = $this->nodeId('root', $rootInfo[5], $rootInfo[0]);
        $this->encodeNode($root, $rootInfo, $rootId, 0, 0, 'root');
        $this->storeSubtreeCaches();

        $full = $this->previousNodes === null;
        $frame = $full
            ? $this->fullFrame($rootId)
            : $this->patchFrame($rootId);
        $this->previousRoot = $rootId;
        $this->previousNodes = $this->nodes;

        return [
            'frame' => $frame,
            'callbacks' => $this->callbacks,
            'full' => $full,
        ];
    }

    /**
     * @param array{0: int, 1: array<int, mixed>, 2: array<int, Closure>, 3: list<Element>, 4: ?string, 5: ?string, 6: ?string} $info
     */
    private function encodeNode(
        Element $element,
        array $info,
        int $id,
        int $parent,
        int $index,
        string $path,
    ): void {
        [$kind, $properties, $events, $children, $key, $dom] = $info;

        // Only the root and keyed/DOM subtrees are ever cached.
        if ($key !== null || $dom !== null || $path === 'root') {
            $cached = ($this->subtreeCache[$element] ?? [])[$path] ?? null;

            if ($cached !== null) {
                $count = count($cached->nodes);
                if ($this->nodeCount + $count > self::MAX_NODES) {
                    throw new LogicException('Pam Native trees cannot exceed 100,000 nodes.');
                }
                // The subtree root id was reserved by nodeId(); any other
                // overlap is an identity collision.
                $overlap = array_intersect_key($cached->nodes, $this->ids);
                if ($overlap !== [] && (count($overlap) > 1 || array_key_first($overlap) !== $id || array_key_first($cached->nodes) !== $id)) {
                    throw new LogicException("Element identity collision at {$path}; assign a unique key.");
                }
                $this->ids += $cached->nodes;
                $this->nodes += $cached->nodes;
                $root = $cached->nodes[array_key_first($cached->nodes)];
                if ($root->index !== $index || $root->parent !== $parent) {
                    // A reused (memoized or keyed) subtree can sit at a new
                    // sibling position under the same path; only its root's
                    // placement changes, its descendants are unchanged.
                    $this->nodes[$root->id] = $root->placedAt($parent, $index);
                }
                $this->nodeCount += $count;
                if ($cached->callbacks !== []) {
                    $this->callbacks += $cached->callbacks;
                }

                return;
            }
        }

        $start = count($this->nodes);
        $callbackStart = count($this->callbacks);

        if (++$this->nodeCount > self::MAX_NODES) {
            throw new LogicException('Pam Native trees cannot exceed 100,000 nodes.');
        }

        foreach ($events as $eventKind => $callback) {
            $this->callbacks[$id.':'.$eventKind] = $callback;
            $properties[self::EVENT_PROPERTIES[$eventKind] ?? self::eventProperty($eventKind)] = true;
        }

        ksort($properties, SORT_NUMERIC);
        $encodedProperties = [];
        $propertyBytes = '';

        foreach ($properties as $property => $value) {
            $encoded = match (true) {
                is_string($value) => self::$encodedStrings[$value] ?? $this->encodeString($value),
                is_int($value) => "\x02".pack('P', $value),
                is_bool($value) => $value ? "\x04\x01" : "\x04\x00",
                default => $this->encodeValue($value),
            };
            $encodedProperties[$property] = $encoded;
            $propertyBytes .= pack('v', $property).$encoded;
        }

        $this->nodes[$id] = new EncodedNode(
            id: $id,
            parent: $parent,
            index: $index,
            kind: $kind,
            properties: $encodedProperties,
            propertyBytes: Wire::u16(count($encodedProperties)).$propertyBytes,
        );

        $segments = [];

        foreach ($children as $childIndex => $child) {
            $childInfo = $child->__pamEncoding();
            $childKind = $childInfo[0];
            $childKey = $childInfo[4];
            $childDom = $childInfo[5];
            $segment = $childDom !== null
                ? 'dom:'.$childDom
                : ($childKey !== null
                    ? 'key:'.$childKey
                    : $childKind.':'.($childInfo[6] ?? $childIndex));
            if (isset($segments[$segment]) && $childKey === null && $childDom === null) {
                // A builder mixed slotted children with positional ones (for
                // example template slot content composed in PHP); fall back to
                // the output position instead of failing on a collision.
                $segment = $childKind.':#'.$childIndex;
            }
            $segments[$segment] = true;
            $childPath = $path.'/'.$segment;
            $childId = $this->nodeId($childPath, $childDom, $childKind);
            $this->encodeNode($child, $childInfo, $childId, $id, $childIndex, $childPath);
        }

        if (
            $path === 'root'
            || (($dom !== null || $key !== null) && $children !== [])
        ) {
            $this->cacheCandidates[] = new SubtreeCacheCandidate(
                element: $element,
                path: $path,
                start: $start,
                length: count($this->nodes) - $start,
                callbackStart: $callbackStart,
                callbackLength: count($this->callbacks) - $callbackStart,
            );
        }
    }

    private function storeSubtreeCaches(): void
    {
        if ($this->cacheCandidates === []) {
            return;
        }

        foreach ($this->cacheCandidates as $candidate) {
            $nodes = array_slice($this->nodes, $candidate->start, $candidate->length, true);
            $callbacks = $candidate->callbackLength === 0
                ? []
                : array_slice($this->callbacks, $candidate->callbackStart, $candidate->callbackLength, true);

            $entries = $this->subtreeCache[$candidate->element] ?? [];
            $entries[$candidate->path] = new EncodedSubtree($nodes, $callbacks);
            $this->subtreeCache[$candidate->element] = $entries;
        }
    }

    private function fullFrame(int $rootId): string
    {
        $frame = Protocol::TREE_MAGIC
            .Wire::u16(Protocol::VERSION)
            .Wire::u64($rootId)
            .Wire::u32($this->nodeCount);
        foreach ($this->nodes as $node) {
            $frame .= $node->bytes();
        }

        return $frame;
    }

    private function encodeNodeBytes(EncodedNode $node): string
    {
        return $node->bytes();
    }

    private function patchFrame(int $rootId): ?string
    {
        $previousNodes = $this->previousNodes;

        if ($previousNodes === null) {
            throw new LogicException('Cannot encode a patch without a previous tree.');
        }

        $removals = [];
        $creates = [];
        $moves = [];
        $updates = [];

        foreach (array_diff_key($previousNodes, $this->nodes) as $id => $_previous) {
            $removals[] = "\x02".pack('P', $id);
        }

        foreach ($this->nodes as $id => $node) {
            $previous = $previousNodes[$id] ?? null;

            if ($previous === $node) {
                continue;
            }

            if ($previous === null) {
                $creates[] = "\x01".$node->bytes();

                continue;
            }

            if (!$node->hasSameTopology($previous)) {
                $moves[] = "\x04".pack('PPV', $id, $node->parent, $node->index);
            }

            if ($previous->properties === $node->properties) {
                continue;
            }

            $keys = $previous->properties + $node->properties;
            ksort($keys, SORT_NUMERIC);

            foreach ($keys as $key => $_) {
                $hadValue = array_key_exists($key, $previous->properties);
                $hasValue = array_key_exists($key, $node->properties);
                $previousValue = $hadValue ? $previous->properties[$key] : null;
                $nextValue = $hasValue ? $node->properties[$key] : null;

                if ($hadValue === $hasValue && $previousValue === $nextValue) {
                    continue;
                }

                $operation = "\x03"
                    .pack('Pv', $id, $key)
                    .($hasValue ? "\x01" : "\x02");

                if ($nextValue !== null) {
                    $operation .= $nextValue;
                }

                $updates[] = $operation;
            }
        }

        $operations = [
            ...$removals,
            ...$creates,
            ...$moves,
            ...$updates,
        ];

        if ($this->previousRoot !== $rootId) {
            $operations[] = "\x05".Wire::u64($rootId);
        }

        if ($operations === []) {
            return null;
        }

        return Protocol::PATCH_MAGIC
            .Wire::u16(Protocol::VERSION)
            .Wire::u32(count($operations))
            .implode('', $operations);
    }

    private function nodeId(string $path, ?string $domIdentity, int $kind): int
    {
        $identity = ($domIdentity === null ? $path : 'dom:'.$domIdentity)
            .'|'.$kind;
        $id = $this->identityIds[$identity]
            ??= (int) hexdec(substr(hash('xxh3', $identity), 0, 15));

        if (
            $id === 0
            || isset($this->ids[$id])
            || (isset($this->idIdentities[$id]) && $this->idIdentities[$id] !== $identity)
        ) {
            throw new LogicException("Element identity collision at {$path}; assign a unique key.");
        }

        $this->idIdentities[$id] = $identity;
        $this->ids[$id] = true;

        return $id;
    }

    private function encodeString(string $value): string
    {
        $encoded = "\x01".Wire::sized($this->validatedText($value));
        if (strlen($value) <= 256) {
            if (count(self::$encodedStrings) >= 8192) {
                self::$encodedStrings = [];
            }
            self::$encodedStrings[$value] = $encoded;
        }

        return $encoded;
    }

    private static function eventProperty(int $kind): int
    {
        EventKind::from($kind);

        throw new LogicException(
            'Runtime events cannot be encoded as element callbacks.',
        );
    }

    private function encodeValue(string|int|float|bool|BinaryValue $value): string
    {
        return match (true) {
            is_string($value) => "\x01".Wire::sized($this->validatedText($value)),
            is_int($value) => "\x02".pack('P', $value),
            is_float($value) => "\x03".pack('e', $this->validatedFloat($value)),
            is_bool($value) => "\x04".($value ? "\x01" : "\x00"),
            $value instanceof BinaryValue => "\x05".Wire::sized($value->bytes),
        };
    }

    private function validatedText(string $value): string
    {
        if (preg_match('//u', $value) !== 1) {
            throw new InvalidArgumentException('Text properties must contain valid UTF-8.');
        }

        return $value;
    }

    private function validatedFloat(float $value): float
    {
        if (!is_finite($value)) {
            throw new InvalidArgumentException('Floating properties must be finite.');
        }

        return $value;
    }
}
