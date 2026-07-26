<?php

declare(strict_types=1);

namespace Pam\Native\Internal;

use Closure;
use LogicException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Protocol;
use Pam\Native\PropKey;
use WeakMap;

final class TreeEncoder
{
    private const MAX_NODES = 100_000;

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

    public function __construct()
    {
        $this->subtreeCache = new WeakMap();
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
        $rootId = $this->nodeId('root', $root);
        $this->encodeNode($root, $rootId, 0, 0, 'root');
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

    private function encodeNode(
        Element $element,
        int $id,
        int $parent,
        int $index,
        string $path,
    ): void {
        $cached = ($this->subtreeCache[$element] ?? [])[$path] ?? null;

        if ($cached !== null) {
            if ($this->nodeCount + count($cached->nodes) > self::MAX_NODES) {
                throw new LogicException('Pam Native trees cannot exceed 100,000 nodes.');
            }

            foreach ($cached->nodes as $offset => $node) {
                $isReservedRoot = $offset === 0 && $node->id === $id;

                if (isset($this->ids[$node->id]) && !$isReservedRoot) {
                    throw new LogicException("Element identity collision at {$path}; assign a unique key.");
                }

                $this->ids[$node->id] = true;
                $this->nodes[$node->id] = $node;
            }
            $this->nodeCount += count($cached->nodes);
            $this->callbacks = [...$this->callbacks, ...$cached->callbacks];

            return;
        }

        $start = count($this->nodes);

        if (++$this->nodeCount > self::MAX_NODES) {
            throw new LogicException('Pam Native trees cannot exceed 100,000 nodes.');
        }

        $properties = $element->properties();

        foreach ($element->events() as $kind => $callback) {
            $this->callbacks[$id.':'.$kind] = $callback;
            $property = match (EventKind::from($kind)) {
                EventKind::Press => PropKey::OnPress,
                EventKind::Change => PropKey::OnChange,
                EventKind::LongPress => PropKey::OnLongPress,
                EventKind::Focus => PropKey::OnFocus,
                EventKind::Blur => PropKey::OnBlur,
                EventKind::Submit => PropKey::OnSubmit,
                EventKind::Scroll => PropKey::OnScroll,
                EventKind::Refresh => PropKey::OnRefresh,
                EventKind::Toggle => PropKey::OnToggle,
                EventKind::EndReached => PropKey::OnEndReached,
                EventKind::DrawerOpen => PropKey::OnDrawerOpen,
                EventKind::DrawerClose => PropKey::OnDrawerClose,
                EventKind::Native => PropKey::OnNativeEvent,
                EventKind::ImageLoadStart => PropKey::OnImageLoadStart,
                EventKind::ImageProgress => PropKey::OnImageProgress,
                EventKind::ImageLoad => PropKey::OnImageLoad,
                EventKind::ImageError => PropKey::OnImageError,
                EventKind::ImageLoadEnd => PropKey::OnImageLoadEnd,
                EventKind::InputEndEditing => PropKey::OnInputEndEditing,
                EventKind::InputSelectionChange =>
                    PropKey::OnInputSelectionChange,
                EventKind::InputContentSizeChange =>
                    PropKey::OnInputContentSizeChange,
                EventKind::InputKeyPress => PropKey::OnInputKeyPress,
                EventKind::PressIn => PropKey::OnPressIn,
                EventKind::PressOut => PropKey::OnPressOut,
                EventKind::PressMove => PropKey::OnPressMove,
                EventKind::ModalRequestClose => PropKey::OnModalRequestClose,
                EventKind::ModalShow => PropKey::OnModalShow,
                EventKind::ModalDismiss => PropKey::OnModalDismiss,
                EventKind::ModalOrientationChange =>
                    PropKey::OnModalOrientationChange,
                EventKind::ClickOutside => PropKey::OnClickOutside,
                EventKind::Intersect => PropKey::OnIntersect,
                EventKind::Mutate => PropKey::OnMutate,
                EventKind::Resize => PropKey::OnResize,
                EventKind::TouchStart => PropKey::OnTouchStart,
                EventKind::TouchMove => PropKey::OnTouchMove,
                EventKind::TouchEnd => PropKey::OnTouchEnd,
                EventKind::Back,
                EventKind::ModuleResult,
                EventKind::AppState,
                EventKind::Dimensions,
                EventKind::MemoryPressure,
                => throw new LogicException(
                    'Runtime events cannot be encoded as element callbacks.',
                ),
            };
            $properties[$property->value] = true;
        }

        ksort($properties, SORT_NUMERIC);
        $encodedProperties = [];

        foreach ($properties as $key => $value) {
            $encodedProperties[$key] = $this->encodeValue($value);
        }

        $this->nodes[$id] = new EncodedNode(
            id: $id,
            parent: $parent,
            index: $index,
            kind: $element->kind()->value,
            properties: $encodedProperties,
        );

        foreach ($element->children() as $childIndex => $child) {
            $childPath = $path.'/'.$this->pathSegment($child, $childIndex);
            $childId = $this->nodeId($childPath, $child);
            $this->encodeNode($child, $childId, $id, $childIndex, $childPath);
        }

        if ($path === 'root' || ($element->elementKey() !== null && $element->children() !== [])) {
            $this->cacheCandidates[] = new SubtreeCacheCandidate(
                element: $element,
                path: $path,
                start: $start,
                length: count($this->nodes) - $start,
            );
        }
    }

    private function storeSubtreeCaches(): void
    {
        if ($this->cacheCandidates === []) {
            return;
        }

        $orderedNodes = array_values($this->nodes);

        foreach ($this->cacheCandidates as $candidate) {
            $nodes = array_slice($orderedNodes, $candidate->start, $candidate->length);
            $nodeIds = [];

            foreach ($nodes as $node) {
                $nodeIds[$node->id] = true;
            }

            $callbacks = [];

            foreach ($this->callbacks as $key => $callback) {
                $separator = strpos($key, ':');
                $nodeId = $separator === false ? 0 : (int) substr($key, 0, $separator);

                if (isset($nodeIds[$nodeId])) {
                    $callbacks[$key] = $callback;
                }
            }

            $entries = $this->subtreeCache[$candidate->element] ?? [];
            $entries[$candidate->path] = new EncodedSubtree($nodes, $callbacks);
            $this->subtreeCache[$candidate->element] = $entries;
        }
    }

    private function fullFrame(int $rootId): string
    {
        return Protocol::TREE_MAGIC
            .Wire::u16(Protocol::VERSION)
            .Wire::u64($rootId)
            .Wire::u32($this->nodeCount)
            .implode('', array_map(
                $this->encodeNodeBytes(...),
                $this->nodes,
            ));
    }

    private function encodeNodeBytes(EncodedNode $node): string
    {
        $chunks = [
            Wire::u64($node->id),
            Wire::u64($node->parent),
            Wire::u32($node->index),
            chr($node->kind),
            Wire::u16(count($node->properties)),
        ];

        foreach ($node->properties as $key => $value) {
            $chunks[] = Wire::u16($key);
            $chunks[] = $value;
        }

        return implode('', $chunks);
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

        foreach ($previousNodes as $id => $_previous) {
            if (!isset($this->nodes[$id])) {
                $removals[] = "\x02".Wire::u64($id);
            }
        }

        foreach ($this->nodes as $id => $node) {
            $previous = $previousNodes[$id] ?? null;

            if ($previous === null) {
                $creates[] = "\x01".$this->encodeNodeBytes($node);

                continue;
            }

            if (!$node->hasSameTopology($previous)) {
                $moves[] = "\x04"
                    .Wire::u64($id)
                    .Wire::u64($node->parent)
                    .Wire::u32($node->index);
            }

            $keys = array_values(array_unique([
                ...array_keys($previous->properties),
                ...array_keys($node->properties),
            ]));
            sort($keys, SORT_NUMERIC);

            foreach ($keys as $key) {
                $hadValue = array_key_exists($key, $previous->properties);
                $hasValue = array_key_exists($key, $node->properties);
                $previousValue = $hadValue ? $previous->properties[$key] : null;
                $nextValue = $hasValue ? $node->properties[$key] : null;

                if ($hadValue === $hasValue && $previousValue === $nextValue) {
                    continue;
                }

                $operation = "\x03"
                    .Wire::u64($id)
                    .Wire::u16($key)
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

    private function pathSegment(Element $element, int $index): string
    {
        return $element->elementKey() !== null
            ? 'key:'.$element->elementKey()
            : $element->kind()->value.':'.$index;
    }

    private function nodeId(string $path, Element $element): int
    {
        $identity = $path.'|'.$element->kind()->value;
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

    private function encodeValue(string|int|float|bool|BinaryValue $value): string
    {
        return match (true) {
            is_string($value) => "\x01".Wire::sized($value),
            is_int($value) => "\x02".pack('P', $value),
            is_float($value) => "\x03".pack('e', $value),
            is_bool($value) => "\x04".($value ? "\x01" : "\x00"),
            $value instanceof BinaryValue => "\x05".Wire::sized($value->bytes),
        };
    }
}
