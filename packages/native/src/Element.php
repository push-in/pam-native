<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use InvalidArgumentException;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\Navigation\SharedTransitionStyle;

abstract class Element implements Renderable
{
    /** @var array<int, string|int|float|bool|BinaryValue> */
    private array $properties = [];

    /** @var list<Element> */
    private array $children = [];

    /** @var array<int, Closure> */
    private array $events = [];

    private ?string $elementKey = null;

    private ?string $domIdentity = null;

    private ?string $domId = null;

    /** @var list<string> */
    private array $domClasses = [];

    /** @var array<string, string> */
    private array $domDataset = [];

    final protected function __construct(private readonly NodeKind $kind)
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
     */
    final public function withThemeDefaults(array $defaultsByKind): static
    {
        $copy = clone $this;
        foreach ($defaultsByKind[$this->kind->value] ?? [] as $key => $value) {
            if (!array_key_exists($key, $copy->properties)) {
                $copy->properties[$key] = $value;
            }
        }
        $copy->children = array_map(
            static fn (Element $child): Element => $child->withThemeDefaults($defaultsByKind),
            $copy->children,
        );

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

    /** @param array<array-key, mixed> $children */
    final protected function withChildren(array $children): static
    {
        $validated = [];

        foreach ($children as $child) {
            if (!$child instanceof Renderable) {
                throw new InvalidArgumentException('Every child must be renderable by Pam Native.');
            }

            $validated[] = $child->toElement();
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
