<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use InvalidArgumentException;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\Navigation\SharedTransitionStyle;

use function array_key_exists;
use function count;
use function in_array;
use function is_string;
use function strlen;

abstract class Element implements Renderable
{
    /** @var array<int, string|int|float|bool|BinaryValue> */
    /** @internal public read for the tree encoder */
    public private(set) array $properties = [];

    /** @var list<Element> */
    /** @internal public read for the tree encoder */
    public private(set) array $children = [];

    /** @var array<int, Closure> */
    /** @internal public read for the tree encoder */
    public private(set) array $events = [];

    /** @internal public read for the tree encoder */
    public private(set) ?string $elementKey = null;

    /** @internal public read for the tree encoder */
    public private(set) ?string $domIdentity = null;

    private ?string $domId = null;

    /** @var list<string> */
    private array $domClasses = [];

    /** @var array<string, string> */
    private array $domDataset = [];

    /**
     * Static position of this element among the slots its parent declared
     * (template position or builder argument position). Conditional siblings
     * that render nothing leave a hole, so unkeyed siblings keep their native
     * identity when a sibling before them appears or disappears.
     */
    /** @internal public read for the tree encoder */
    public private(set) ?string $identitySlot = null;

    /** Likely to be the same object in the next frame (memoized component). */
    /** @internal public read for the tree encoder */
    public private(set) bool $reusable = false;

    /**
     * @internal Wire-encoded properties computed by the template renderer:
     * [the properties array they encode, encoded values by key in key order,
     * their wire bytes when precomputed]. Valid only while the element still
     * holds that same array.
     *
     * @var array{0: array<int, mixed>, 1: array<int, string>, 2: ?string}|null
     */
    public private(set) ?array $encodedProperties = null;

    /** @var \WeakMap<Element, array<string, Element>>|null */
    private static ?\WeakMap $slottedCopies = null;

    final protected function __construct(public readonly NodeKind $kind)
    {
    }

    final public function key(string $key): static
    {
        if ($key === '' || strlen($key) > 128) {
            throw new InvalidArgumentException('Element keys must contain between 1 and 128 bytes.');
        }

        $copy = clone $this;
        $copy->elementKey = $key;

        return $copy;
    }

    final public function id(string $id): static
    {
        if (preg_match('/^[A-Za-z][A-Za-z0-9_.:-]{0,127}$/D', $id) !== 1) {
            throw new InvalidArgumentException('DOM ids must use a bounded portable identifier.');
        }

        $copy = clone $this;
        $copy->domId = $id;

        return $copy->testId($id);
    }

    final public function class(string ...$classes): static
    {
        $copy = clone $this;
        foreach ($classes as $class) {
            foreach (preg_split('/\s+/', trim($class), -1, PREG_SPLIT_NO_EMPTY) ?: [] as $token) {
                if (preg_match('/^[A-Za-z_][A-Za-z0-9_-]{0,127}$/D', $token) !== 1) {
                    throw new InvalidArgumentException("Invalid DOM class token {$token}.");
                }
                if (!in_array($token, $copy->domClasses, true)) {
                    $copy->domClasses[] = $token;
                }
            }
        }

        return $copy;
    }

    final public function data(string $name, string $value): static
    {
        if (preg_match('/^[a-z][a-z0-9-]{0,63}$/D', $name) !== 1) {
            throw new InvalidArgumentException('DOM data names must use lowercase kebab-case.');
        }
        if (strlen($value) > 4_096 || preg_match('//u', $value) !== 1) {
            throw new InvalidArgumentException('DOM data values must be valid UTF-8 up to 4 KiB.');
        }

        $copy = clone $this;
        $copy->domDataset[$name] = $value;

        return $copy;
    }

    final public function style(Style $style): static
    {
        $copy = clone $this;

        foreach ($style->properties() as $key => $value) {
            $copy->properties[$key] = $value;
        }

        return $copy;
    }

    final public function accessibilityLabel(string $label): static
    {
        return $this->withProperty(PropKey::AccessibilityLabel, $label);
    }

    final public function accessibilityHint(string $hint): static
    {
        return $this->withProperty(PropKey::AccessibilityHint, $hint);
    }

    final public function accessibilityRole(AccessibilityRole $role): static
    {
        return $this->withProperty(PropKey::AccessibilityRole, $role->value);
    }

    final public function accessible(bool $accessible = true): static
    {
        return $this->withProperty(PropKey::Accessible, $accessible);
    }

    final public function accessibilityLiveRegion(
        AccessibilityLiveRegion $region,
    ): static {
        return $this->withProperty(
            PropKey::AccessibilityLiveRegion,
            $region->value,
        );
    }

    final public function accessibilityImportance(
        AccessibilityImportance $importance,
    ): static {
        return $this->withProperty(
            PropKey::AccessibilityImportance,
            $importance->value,
        );
    }

    final public function accessibilityExpanded(bool $expanded): static
    {
        return $this->withProperty(PropKey::AccessibilityExpanded, $expanded);
    }

    final public function accessibilityBusy(bool $busy = true): static
    {
        return $this->withProperty(PropKey::AccessibilityBusy, $busy);
    }

    final public function accessibilityChecked(
        AccessibilityCheckedState $state,
    ): static {
        return $this->withProperty(
            PropKey::AccessibilityCheckedState,
            $state->value,
        );
    }

    final public function accessibilityValue(
        float $minimum,
        float $maximum,
        float $current,
        ?string $text = null,
    ): static {
        if ($minimum > $maximum || $current < $minimum || $current > $maximum) {
            throw new InvalidArgumentException(
                'Accessibility range must satisfy minimum <= current <= maximum.',
            );
        }

        $element = $this
            ->withProperty(PropKey::AccessibilityValueMin, $minimum)
            ->withProperty(PropKey::AccessibilityValueMax, $maximum)
            ->withProperty(PropKey::AccessibilityValueNow, $current);

        return $text === null
            ? $element
            : $element->withProperty(PropKey::AccessibilityValueText, $text);
    }

    final public function accessibilityActions(AccessibilityAction ...$actions): static
    {
        if ($actions === [] || count($actions) > 8) {
            throw new InvalidArgumentException(
                'Elements must expose between 1 and 8 accessibility actions.',
            );
        }
        $names = array_map(static fn (AccessibilityAction $action): string => $action->name, $actions);
        if (count(array_unique($names)) !== count($names)) {
            throw new InvalidArgumentException('Accessibility action names must be unique per element.');
        }
        $encoded = json_encode(
            array_map(static fn (AccessibilityAction $action): array => $action->toArray(), $actions),
            JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE,
        );

        return $this->withProperty(PropKey::AccessibilityActions, $encoded)->accessible();
    }

    final public function onAccessibilityAction(Closure $handler): static
    {
        return $this->withEvent(EventKind::AccessibilityAction, $handler);
    }

    final public function testId(string $id): static
    {
        return $this->withProperty(PropKey::TestId, $id);
    }

    /**
     * Preserves the visual identity of this element across a native route
     * transition. Matching tags are measured and animated entirely by UIKit or
     * Android's UI thread; PHP is never involved per frame.
     */
    final public function sharedTransition(
        string $tag,
        ?SharedTransitionStyle $style = null,
    ): static
    {
        if (preg_match('/^[A-Za-z0-9][A-Za-z0-9_.:-]{0,127}$/', $tag) !== 1) {
            throw new InvalidArgumentException('Shared transition tags must be bounded safe identifiers.');
        }

        $element = $this->withProperty(PropKey::SharedTransitionTag, $tag);
        return $style === null
            ? $element
            : $element->withProperty(PropKey::SharedTransitionConfig, $style->toJson());
    }

    /**
     * Names this view for native motion: drag drivers, drag targets and tap
     * effects resolve `nativeRef` inside their gesture detector.
     */
    final public function nativeRef(string $ref): static
    {
        return $this->withProperty(PropKey::NativeRef, \Pam\Native\Animation\Drag::ref($ref));
    }

    /**
     * Plays a UI-thread animation program. It replays only when the
     * animation identity (program + `key()`) changes.
     */
    final public function animation(\Pam\Native\Animation\Animation $animation): static
    {
        return $this->withProperty(PropKey::AnimationProgram, $animation->encode());
    }

    /** Replays the attached animation program or keyframes whenever [key] changes. */
    final public function replayKey(int $key): static
    {
        return $this->withProperty(PropKey::AnimationRestartKey, max(0, $key));
    }

    /**
     * CSS transition with per-property durations, delays and easings plus
     * `spring(mass stiffness damping)`, e.g.
     * `transform 300ms spring(1 260 18), opacity 120ms ease-out 40ms`.
     */
    final public function transition(string $css): static
    {
        $spec = \Pam\Native\Animation\Transition::apply('', 'transition', $css);

        return $this
            ->withProperty(PropKey::TransitionSpec, $spec)
            ->withProperty(PropKey::AnimateChanges, \Pam\Native\Animation\Transition::animates($spec))
            ->withProperty(
                PropKey::AnimationDurationMs,
                max(1, min(10_000, \Pam\Native\Animation\Transition::maxDuration($spec))),
            );
    }

    /** RN `onTextLayout`: wrapped line count, truncation and line widths. */
    final public function onTextLayout(Closure $handler): static
    {
        return $this->withEvent(
            EventKind::TextLayout,
            static fn (string $payload = ''): mixed => $handler(\Pam\Native\TextLayoutEvent::fromPayload($payload)),
        );
    }

    final public function enabled(bool $enabled): static
    {
        return $this->withProperty(PropKey::Enabled, $enabled);
    }

    final public function visible(bool $visible): static
    {
        return $this->withProperty(PropKey::Visible, $visible);
    }

    final public function collapsable(bool $collapsable = true): static
    {
        return $this->withProperty(PropKey::Collapsable, $collapsable);
    }

    final public function animate(
        int $durationMs = 180,
        AnimationEasing $easing = AnimationEasing::EaseInOut,
    ): static {
        return $this
            ->withProperty(PropKey::AnimateChanges, true)
            ->withProperty(PropKey::AnimationDurationMs, max(1, min(10_000, $durationMs)))
            ->withProperty(PropKey::AnimationEasing, $easing->value);
    }

    final public function motion(
        MotionPreset $preset,
        int $durationMs = 240,
        AnimationEasing $easing = AnimationEasing::EaseOut,
    ): static {
        return $this
            ->withProperty(PropKey::AnimationKind, $preset->animationKind()->value)
            ->withProperty(PropKey::AnimationDurationMs, max(1, min(2_000, $durationMs)))
            ->withProperty(PropKey::AnimationEasing, $easing->value);
    }

    final public function property(
        PropKey $key,
        string|int|float|bool|BinaryValue $value,
    ): static {
        return $this->withProperty($key, $value);
    }

    final public function on(EventKind $kind, Closure $handler): static
    {
        return $this->withEvent($kind, $handler);
    }

    /**
     * Sticky header inside a ScrollView or VirtualizedList (React Native
     * `stickyHeaderIndices`): pinned to the top once scrolled past, until the
     * next sticky sibling pushes it away.
     */
    final public function stickyHeader(bool $sticky = true): static
    {
        return $this->withProperty(PropKey::StickyHeader, $sticky);
    }

    /** VirtualizedList item spanning all columns (list header/footer). */
    final public function fullSpan(bool $fullSpan = true): static
    {
        return $this->withProperty(PropKey::ListFullSpan, $fullSpan);
    }

    /**
     * Keyed section of a VirtualizedList row. Rows of a section other than
     * the list's {@see \Pam\Native\UI\VirtualizedList::activeSection()}
     * stay mounted but hidden: their layouts, native views and decoded
     * images are kept, so switching back is a native visibility swap with
     * no rebuild. Rows without a section (header, tab rail, footer) are
     * always shown. Each section keeps its own scroll position.
     */
    final public function listSection(string|int $section): static
    {
        return $this->withProperty(PropKey::ListSection, (string) $section);
    }

    /**
     * React Native `onLayout`: receives a {@see LayoutEvent} with the frame
     * relative to the parent on mount and after every frame change.
     *
     * @param Closure(LayoutEvent): void $handler
     */
    final public function onLayout(Closure $handler): static
    {
        return $this->withEvent(
            EventKind::Layout,
            static function (mixed $payload = '') use ($handler): void {
                $handler(LayoutEvent::fromPayload(is_string($payload) ? $payload : ''));
            },
        );
    }

    final public function toElement(): Element
    {
        return $this;
    }

    /** @return list<Element> */
    final public function children(): array
    {
        return $this->children;
    }

    /** @return array<int, string|int|float|bool|BinaryValue> */
    final public function properties(): array
    {
        return $this->properties;
    }

    /** @return array<int, Closure> */
    final public function events(): array
    {
        return $this->events;
    }

    final public function kind(): NodeKind
    {
        return $this->kind;
    }

    final public function elementKey(): ?string
    {
        return $this->elementKey;
    }

    final public function domIdentity(): ?string
    {
        return $this->domIdentity;
    }

    final public function domId(): ?string
    {
        return $this->domId;
    }

    /** @return list<string> */
    final public function domClasses(): array
    {
        return $this->domClasses;
    }

    /** @return array<string, string> */
    final public function domDataset(): array
    {
        return $this->domDataset;
    }

    /**
     * @internal Everything the tree encoder reads, in one call:
     * [kind, properties, events, children, key, DOM identity, identity slot,
     * reusable].
     *
     * @return array{0: int, 1: array<int, string|int|float|bool|BinaryValue>, 2: array<int, Closure>, 3: list<Element>, 4: ?string, 5: ?string, 6: ?string, 7: bool}
     */
    final public function __pamEncoding(): array
    {
        return [
            $this->kind->value,
            $this->properties,
            $this->events,
            $this->children,
            $this->elementKey,
            $this->domIdentity,
            $this->identitySlot,
            $this->reusable,
        ];
    }

    /**
     * @internal
     * @param array<int, string> $encoded
     */
    final public function __pamEncoded(array $encoded, ?string $bytes = null): void
    {
        $this->encodedProperties = [$this->properties, $encoded, $bytes];
    }

    /** @internal Marks an element the renderer expects to reuse across frames. */
    final public function __pamMarkReusable(): void
    {
        $this->reusable = true;
    }

    /** @internal Static slot used by the tree encoder for unkeyed identity. */
    final public function identitySlot(): ?string
    {
        return $this->identitySlot;
    }

    /**
     * @internal Places this element at a static parent slot. Copies are
     * memoized per source element so reused (memoized) subtrees keep their
     * object identity and their encoder caches across renders.
     */
    final public function withIdentitySlot(string $slot): static
    {
        if ($this->identitySlot === $slot) {
            return $this;
        }
        self::$slottedCopies ??= new \WeakMap();
        $copies = self::$slottedCopies[$this] ?? [];
        if (isset($copies[$slot])) {
            /** @var static */
            return $copies[$slot];
        }
        $copy = clone $this;
        $copy->identitySlot = $slot;
        if (count($copies) >= 8) {
            $copies = [];
        }
        $copies[$slot] = $copy;
        self::$slottedCopies[$this] = $copies;

        return $copy;
    }

    /**
     * @internal Places an element the template renderer just built (and
     * nobody else references yet) at its static slot without copying it.
     */
    final public function __pamPlaceAt(string $slot): static
    {
        $this->identitySlot = $slot;

        return $this;
    }

    /**
     * @internal Sets several resolved native properties with one copy.
     *
     * @param array<int, string|int|float|bool|BinaryValue> $properties
     */
    final public function __pamWithProperties(array $properties): static
    {
        $copy = clone $this;
        foreach ($properties as $key => $value) {
            if (is_string($value) && strlen($value) > 1_048_576) {
                throw new InvalidArgumentException('String properties cannot exceed one megabyte.');
            }
            $copy->properties[$key] = $value;
        }

        return $copy;
    }

    /**
     * @internal Applies DOM class tokens (when given) and resolved native
     * properties (null entries skipped) with one copy.
     *
     * @param list<string>|null $classes
     * @param array<int, string|int|float|bool|BinaryValue|null> $properties
     */
    final public function __pamDecorate(
        ?array $classes,
        array $properties,
        ?string $key = null,
        array $events = [],
    ): static {
        $copy = clone $this;
        if ($key !== null) {
            $copy->elementKey = $key;
        }
        if ($classes !== null) {
            $copy->domClasses = $classes;
        }
        foreach ($properties as $property => $value) {
            if ($value === null) {
                continue;
            }
            if (is_string($value) && strlen($value) > 1_048_576) {
                throw new InvalidArgumentException('String properties cannot exceed one megabyte.');
            }
            $copy->properties[$property] = $value;
        }
        foreach ($events as $kind => $handler) {
            $copy->events[$kind] = $handler;
            $copy->properties[\Pam\Native\Internal\EventProperties::MAP[$kind]] = true;
        }

        return $copy;
    }

    /**
     * @internal Builds an element the template renderer resolved completely:
     * validated children, final properties (in application order), DOM
     * class tokens, key and events, as make() followed by the setters would.
     *
     * @param list<Element> $children
     * @param array<int, string|int|float|bool|BinaryValue> $properties
     * @param list<string>|null $classes
     * @param array<int, Closure> $events
     */
    final public static function __pamCreate(
        NodeKind $kind,
        array $children,
        array $properties,
        ?array $classes = null,
        ?string $key = null,
        array $events = [],
    ): static {
        $element = new static($kind);
        $element->children = $children;
        $element->properties = $properties;
        if ($classes !== null) {
            $element->domClasses = $classes;
        }
        $element->elementKey = $key;
        foreach ($events as $eventKind => $handler) {
            $element->events[$eventKind] = $handler;
            $element->properties[\Pam\Native\Internal\EventProperties::MAP[$eventKind]] = true;
        }

        return $element;
    }

    /**
     * @internal Sets already validated, de-duplicated DOM class tokens on an
     * element without classes.
     *
     * @param list<string> $classes
     */
    final public function __pamWithDomClasses(array $classes): static
    {
        $copy = clone $this;
        $copy->domClasses = $classes;

        return $copy;
    }

    /** @internal Visual DOM retained-tree operation. */
    final public function domWithIdentity(string $identity): static
    {
        if (preg_match('/^n[1-9][0-9]{0,18}$/D', $identity) !== 1) {
            throw new InvalidArgumentException('DOM identities must be positive bounded handles.');
        }
        $copy = clone $this;
        $copy->domIdentity = $identity;

        return $copy;
    }

    /** @internal Visual DOM retained-tree operation. @param list<Element> $children */
    final public function domWithChildren(array $children): static
    {
        return $this->withChildren($children);
    }

    /** @internal Visual DOM retained-tree operation. */
    final public function domWithProperty(
        PropKey $key,
        string|int|float|bool|BinaryValue $value,
    ): static {
        return $this->withProperty($key, $value);
    }

    /** @internal Visual DOM retained-tree operation. */
    final public function domWithoutProperty(PropKey $key): static
    {
        $copy = clone $this;
        unset($copy->properties[$key->value]);

        return $copy;
    }

    /** @internal Visual DOM retained-tree operation. */
    final public function domWithEvent(EventKind $kind, Closure $handler): static
    {
        return $this->withEvent($kind, $handler);
    }

    /** @internal Visual DOM retained-tree operation. @param list<string> $classes */
    final public function domWithClasses(array $classes): static
    {
        $copy = clone $this;
        $copy->domClasses = [];

        return $copy->class(...$classes);
    }

    /** @internal Visual DOM retained-tree operation. */
    final public function domWithoutData(string $name): static
    {
        $copy = clone $this;
        unset($copy->domDataset[$name]);

        return $copy;
    }

    /**
     * Applies semantic theme defaults without replacing authored properties.
     * Descendants are handled in one retained-tree pass before encoding.
     *
     * @param array<int, array<int, string|int|float|bool>> $defaultsByKind
     * @param \WeakMap<Element, Element>|null $memo themed copies for one theme
     */
    final public function withThemeDefaults(array $defaultsByKind, ?\WeakMap $memo = null): static
    {
        if ($memo !== null && isset($memo[$this])) {
            /** @var static */
            return $memo[$this];
        }
        $copy = clone $this;
        foreach ($defaultsByKind[$this->kind->value] ?? [] as $key => $value) {
            if (!array_key_exists($key, $copy->properties)) {
                $copy->properties[$key] = $value;
            }
        }
        $copy->children = array_map(
            static fn (Element $child): Element => $child->withThemeDefaults($defaultsByKind, $memo),
            $copy->children,
        );
        if ($memo !== null) {
            // Elements are immutable, so a reused (memoized) subtree reuses
            // its themed copy instead of being cloned again every render.
            $memo[$this] = $copy;
        }

        return $copy;
    }

    final protected function withProperty(
        PropKey $key,
        string|int|float|bool|BinaryValue $value,
    ): static {
        if (is_string($value) && strlen($value) > 1_048_576) {
            throw new InvalidArgumentException('String properties cannot exceed one megabyte.');
        }

        $copy = clone $this;
        $copy->properties[$key->value] = $value;

        return $copy;
    }

    /**
     * Null and false children are holes: they render nothing but keep their
     * position, so the following unkeyed siblings keep their native identity
     * when a conditional child appears or disappears.
     *
     * @param array<array-key, mixed> $children
     */
    final protected function withChildren(array $children): static
    {
        $validated = [];
        $holes = false;

        foreach ($children as $child) {
            if ($child === null || $child === false) {
                $holes = true;
                continue;
            }
            if (!$child instanceof Renderable) {
                throw new InvalidArgumentException('Every child must be renderable by Pam Native.');
            }
        }

        if ($holes) {
            $position = 0;
            foreach ($children as $child) {
                if ($child !== null && $child !== false) {
                    $element = $child->toElement();
                    $validated[] = $element->identitySlot === null
                        ? $element->withIdentitySlot((string) $position)
                        : $element;
                }
                $position++;
            }
        } else {
            foreach ($children as $child) {
                $validated[] = $child->toElement();
            }
        }

        $copy = clone $this;
        $copy->children = $validated;

        return $copy;
    }

    final protected function withEvent(EventKind $kind, Closure $handler): static
    {
        $copy = clone $this;
        $copy->events[$kind->value] = $handler;
        $property = match ($kind) {
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
            EventKind::InputSelectionChange => PropKey::OnInputSelectionChange,
            EventKind::InputContentSizeChange => PropKey::OnInputContentSizeChange,
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
            EventKind::GestureBegin => PropKey::OnGestureBegin,
            EventKind::GestureUpdate => PropKey::OnGestureUpdate,
            EventKind::GestureEnd => PropKey::OnGestureEnd,
            EventKind::GestureCancel => PropKey::OnGestureCancel,
            EventKind::BottomSheetChange => PropKey::OnBottomSheetChange,
            EventKind::BottomSheetDismiss => PropKey::OnBottomSheetDismiss,
            EventKind::WebViewLoad => PropKey::OnWebViewLoad,
            EventKind::WebViewError => PropKey::OnWebViewError,
            EventKind::WebViewMessage => PropKey::OnWebViewMessage,
            EventKind::MediaReady => PropKey::OnMediaReady,
            EventKind::MediaProgress => PropKey::OnMediaProgress,
            EventKind::MediaEnd => PropKey::OnMediaEnd,
            EventKind::MediaError => PropKey::OnMediaError,
            EventKind::DragStart => PropKey::OnDragStart,
            EventKind::DragEnd => PropKey::OnDragEnd,
            EventKind::Drop => PropKey::OnDrop,
            EventKind::MenuAction => PropKey::OnMenuAction,
            EventKind::NavigationGesturePop => PropKey::OnNavigationGesturePop,
            EventKind::AnimationComplete => PropKey::OnAnimationComplete,
            EventKind::MediaCacheHit => PropKey::OnMediaCacheHit,
            EventKind::MediaCacheMiss => PropKey::OnMediaCacheMiss,
            EventKind::MediaCacheProgress => PropKey::OnMediaCacheProgress,
            EventKind::MediaCacheReady => PropKey::OnMediaCacheReady,
            EventKind::AccessibilityAction => PropKey::OnAccessibilityAction,
            EventKind::SpanPress => PropKey::OnSpanPress,
            EventKind::Layout => PropKey::OnLayout,
            EventKind::MediaBuffering => PropKey::OnMediaBuffering,
            EventKind::MediaLoadStart => PropKey::OnMediaLoadStart,
            EventKind::DoubleTap => PropKey::OnDoubleTap,
            EventKind::GestureSettle => PropKey::OnGestureSettle,
            EventKind::ScrollBeginDrag => PropKey::OnScrollBeginDrag,
            EventKind::ScrollEndDrag => PropKey::OnScrollEndDrag,
            EventKind::MomentumScrollEnd => PropKey::OnMomentumScrollEnd,
            EventKind::TextLayout => PropKey::OnTextLayout,
            EventKind::Back,
            EventKind::ModuleResult,
            EventKind::AppState,
            EventKind::Dimensions,
            EventKind::MemoryPressure,
            => throw new InvalidArgumentException(
                'This runtime event cannot be attached to an element.',
            ),
        };
        $copy->properties[$property->value] = true;

        return $copy;
    }
}
