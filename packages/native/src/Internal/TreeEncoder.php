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

    /** EventKind value => callback PropKey value. */
    private const EVENT_PROPERTIES = [
        EventKind::Press->value => PropKey::OnPress->value,
        EventKind::Change->value => PropKey::OnChange->value,
        EventKind::LongPress->value => PropKey::OnLongPress->value,
        EventKind::Focus->value => PropKey::OnFocus->value,
        EventKind::Blur->value => PropKey::OnBlur->value,
        EventKind::Submit->value => PropKey::OnSubmit->value,
        EventKind::Scroll->value => PropKey::OnScroll->value,
        EventKind::Refresh->value => PropKey::OnRefresh->value,
        EventKind::Toggle->value => PropKey::OnToggle->value,
        EventKind::EndReached->value => PropKey::OnEndReached->value,
        EventKind::DrawerOpen->value => PropKey::OnDrawerOpen->value,
        EventKind::DrawerClose->value => PropKey::OnDrawerClose->value,
        EventKind::Native->value => PropKey::OnNativeEvent->value,
        EventKind::ImageLoadStart->value => PropKey::OnImageLoadStart->value,
        EventKind::ImageProgress->value => PropKey::OnImageProgress->value,
        EventKind::ImageLoad->value => PropKey::OnImageLoad->value,
        EventKind::ImageError->value => PropKey::OnImageError->value,
        EventKind::ImageLoadEnd->value => PropKey::OnImageLoadEnd->value,
        EventKind::InputEndEditing->value => PropKey::OnInputEndEditing->value,
        EventKind::InputSelectionChange->value => PropKey::OnInputSelectionChange->value,
        EventKind::InputContentSizeChange->value => PropKey::OnInputContentSizeChange->value,
        EventKind::InputKeyPress->value => PropKey::OnInputKeyPress->value,
        EventKind::PressIn->value => PropKey::OnPressIn->value,
        EventKind::PressOut->value => PropKey::OnPressOut->value,
        EventKind::PressMove->value => PropKey::OnPressMove->value,
        EventKind::ModalRequestClose->value => PropKey::OnModalRequestClose->value,
        EventKind::ModalShow->value => PropKey::OnModalShow->value,
        EventKind::ModalDismiss->value => PropKey::OnModalDismiss->value,
        EventKind::ModalOrientationChange->value => PropKey::OnModalOrientationChange->value,
        EventKind::ClickOutside->value => PropKey::OnClickOutside->value,
        EventKind::Intersect->value => PropKey::OnIntersect->value,
        EventKind::Mutate->value => PropKey::OnMutate->value,
        EventKind::Resize->value => PropKey::OnResize->value,
        EventKind::TouchStart->value => PropKey::OnTouchStart->value,
        EventKind::TouchMove->value => PropKey::OnTouchMove->value,
        EventKind::TouchEnd->value => PropKey::OnTouchEnd->value,
        EventKind::GestureBegin->value => PropKey::OnGestureBegin->value,
        EventKind::GestureUpdate->value => PropKey::OnGestureUpdate->value,
        EventKind::GestureEnd->value => PropKey::OnGestureEnd->value,
        EventKind::GestureCancel->value => PropKey::OnGestureCancel->value,
        EventKind::BottomSheetChange->value => PropKey::OnBottomSheetChange->value,
        EventKind::BottomSheetDismiss->value => PropKey::OnBottomSheetDismiss->value,
        EventKind::WebViewLoad->value => PropKey::OnWebViewLoad->value,
        EventKind::WebViewError->value => PropKey::OnWebViewError->value,
        EventKind::WebViewMessage->value => PropKey::OnWebViewMessage->value,
        EventKind::MediaReady->value => PropKey::OnMediaReady->value,
        EventKind::MediaProgress->value => PropKey::OnMediaProgress->value,
        EventKind::MediaEnd->value => PropKey::OnMediaEnd->value,
        EventKind::MediaError->value => PropKey::OnMediaError->value,
        EventKind::DragStart->value => PropKey::OnDragStart->value,
        EventKind::DragEnd->value => PropKey::OnDragEnd->value,
        EventKind::Drop->value => PropKey::OnDrop->value,
        EventKind::MenuAction->value => PropKey::OnMenuAction->value,
        EventKind::NavigationGesturePop->value => PropKey::OnNavigationGesturePop->value,
        EventKind::AnimationComplete->value => PropKey::OnAnimationComplete->value,
        EventKind::MediaCacheHit->value => PropKey::OnMediaCacheHit->value,
        EventKind::MediaCacheMiss->value => PropKey::OnMediaCacheMiss->value,
        EventKind::MediaCacheProgress->value => PropKey::OnMediaCacheProgress->value,
        EventKind::MediaCacheReady->value => PropKey::OnMediaCacheReady->value,
        EventKind::AccessibilityAction->value => PropKey::OnAccessibilityAction->value,
        EventKind::SpanPress->value => PropKey::OnSpanPress->value,
        EventKind::Layout->value => PropKey::OnLayout->value,
        EventKind::MediaBuffering->value => PropKey::OnMediaBuffering->value,
        EventKind::MediaLoadStart->value => PropKey::OnMediaLoadStart->value,
        EventKind::DoubleTap->value => PropKey::OnDoubleTap->value,
        EventKind::GestureSettle->value => PropKey::OnGestureSettle->value,
        EventKind::ScrollBeginDrag->value => PropKey::OnScrollBeginDrag->value,
        EventKind::ScrollEndDrag->value => PropKey::OnScrollEndDrag->value,
        EventKind::MomentumScrollEnd->value => PropKey::OnMomentumScrollEnd->value,
        EventKind::TextLayout->value => PropKey::OnTextLayout->value,
    ];

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
            $count = count($cached->nodes);
            if ($this->nodeCount + $count > self::MAX_NODES) {
                throw new LogicException('Pam Native trees cannot exceed 100,000 nodes.');
            }
            // The subtree root id was reserved by nodeId(); any other overlap
            // is an identity collision.
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

        $start = count($this->nodes);
        $callbackStart = count($this->callbacks);

        if (++$this->nodeCount > self::MAX_NODES) {
            throw new LogicException('Pam Native trees cannot exceed 100,000 nodes.');
        }

        $properties = $element->properties();

        foreach ($element->events() as $kind => $callback) {
            $this->callbacks[$id.':'.$kind] = $callback;
            $property = self::EVENT_PROPERTIES[$kind] ?? self::eventProperty($kind);
            $properties[$property] = true;
        }

        ksort($properties, SORT_NUMERIC);
        $encodedProperties = [];

        foreach ($properties as $key => $value) {
            $encodedProperties[$key] = match (true) {
                is_string($value) => self::$encodedStrings[$value] ?? $this->encodeString($value),
                is_int($value) => "\x02".pack('P', $value),
                is_bool($value) => $value ? "\x04\x01" : "\x04\x00",
                default => $this->encodeValue($value),
            };
        }

        $this->nodes[$id] = new EncodedNode(
            id: $id,
            parent: $parent,
            index: $index,
            kind: $element->kind()->value,
            properties: $encodedProperties,
        );

        $segments = [];

        foreach ($element->children() as $childIndex => $child) {
            $segment = $this->pathSegment($child, $childIndex);
            if (isset($segments[$segment]) && $child->elementKey() === null && $child->domIdentity() === null) {
                // A builder mixed slotted children with positional ones (for
                // example template slot content composed in PHP); fall back to
                // the output position instead of failing on a collision.
                $segment = $child->kind()->value.':#'.$childIndex;
            }
            $segments[$segment] = true;
            $childPath = $path.'/'.$segment;
            $childId = $this->nodeId($childPath, $child);
            $this->encodeNode($child, $childId, $id, $childIndex, $childPath);
        }

        if (
            $path === 'root'
            || (($element->domIdentity() !== null || $element->elementKey() !== null) && $element->children() !== [])
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

    /**
     * Unkeyed children are identified by their static slot (template or
     * builder argument position), not by their output index: a conditional
     * sibling that renders nothing leaves a hole, so the siblings after it keep
     * their native identity (React-like reconciliation).
     */
    private function pathSegment(Element $element, int $index): string
    {
        return $element->domIdentity() !== null
            ? 'dom:'.$element->domIdentity()
            : ($element->elementKey() !== null
            ? 'key:'.$element->elementKey()
            : $element->kind()->value.':'.($element->identitySlot() ?? $index));
    }

    private function nodeId(string $path, Element $element): int
    {
        $identity = ($element->domIdentity() === null ? $path : 'dom:'.$element->domIdentity())
            .'|'.$element->kind()->value;
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
