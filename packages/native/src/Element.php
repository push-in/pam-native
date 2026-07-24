<?php

declare(strict_types=1);

namespace Pam\Native;

use Closure;
use InvalidArgumentException;
use Pam\Native\Internal\BinaryValue;

abstract class Element implements Renderable
{
    /** @var array<int, string|int|float|bool|BinaryValue> */
    private array $properties = [];

    /** @var list<Element> */
    private array $children = [];

    /** @var array<int, Closure> */
    private array $events = [];

    private ?string $elementKey = null;

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

    final public function testId(string $id): static
    {
        return $this->withProperty(PropKey::TestId, $id);
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
