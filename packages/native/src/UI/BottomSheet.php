<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use InvalidArgumentException;
use Pam\Native\BottomSheetEvent;
use Pam\Native\BottomSheetKeyboardBehavior;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\BinaryValue;
use Pam\Native\ModalAnimationType;
use Pam\Native\ModalPresentation;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class BottomSheet extends Element
{
    /**
     * @param list<float> $snapPoints Fractions of the available height in ascending order.
     */
    public static function make(
        Renderable $content,
        array $snapPoints = [0.5, 0.9],
        int $index = 0,
        bool $visible = true,
    ): self {
        $points = self::validateSnapPoints($snapPoints);
        if ($index < 0 || $index >= count($points)) {
            throw new InvalidArgumentException('Bottom Sheet index must reference a snap point.');
        }

        return (new self(NodeKind::Modal))
            ->withChildren([$content])
            ->withProperty(PropKey::Visible, $visible)
            ->withProperty(
                PropKey::ModalPresentation,
                ModalPresentation::Sheet->value,
            )
            ->withProperty(
                PropKey::ModalAnimationType,
                ModalAnimationType::Slide->value,
            )
            ->withProperty(PropKey::ModalAllowSwipeDismissal, true)
            ->withProperty(
                PropKey::BottomSheetSnapPoints,
                new BinaryValue(self::encodeSnapPoints($points)),
            )
            ->withProperty(PropKey::BottomSheetIndex, $index)
            ->withProperty(PropKey::BottomSheetDismissible, true)
            ->withProperty(PropKey::BottomSheetBackdropDismiss, true)
            ->withProperty(PropKey::BottomSheetHandleVisible, true)
            ->withProperty(PropKey::BottomSheetDragEnabled, true)
            ->withProperty(PropKey::BottomSheetCornerRadius, 20.0)
            ->withProperty(
                PropKey::BottomSheetKeyboardBehavior,
                BottomSheetKeyboardBehavior::Interactive->value,
            );
    }

    public function index(int $index): self
    {
        return $this->withProperty(PropKey::BottomSheetIndex, max(0, $index));
    }

    public function dismissible(bool $dismissible = true): self
    {
        return $this
            ->withProperty(PropKey::BottomSheetDismissible, $dismissible)
            ->withProperty(PropKey::ModalAllowSwipeDismissal, $dismissible);
    }

    public function backdropDismiss(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::BottomSheetBackdropDismiss, $enabled);
    }

    public function handleVisible(bool $visible = true): self
    {
        return $this->withProperty(PropKey::BottomSheetHandleVisible, $visible);
    }

    public function dragEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::BottomSheetDragEnabled, $enabled);
    }

    public function cornerRadius(float $radius): self
    {
        return $this->withProperty(
            PropKey::BottomSheetCornerRadius,
            max(0.0, min(128.0, $radius)),
        );
    }

    public function keyboardBehavior(BottomSheetKeyboardBehavior $behavior): self
    {
        return $this->withProperty(
            PropKey::BottomSheetKeyboardBehavior,
            $behavior->value,
        );
    }

    /** @param Closure(BottomSheetEvent): void $handler */
    public function onChange(Closure $handler): self
    {
        return $this->withEvent(
            EventKind::BottomSheetChange,
            static fn (string $payload): mixed => $handler(
                BottomSheetEvent::fromPayload($payload),
            ),
        );
    }

    /** @param Closure(): void $handler */
    public function onDismiss(Closure $handler): self
    {
        return $this->withEvent(
            EventKind::BottomSheetDismiss,
            static fn (string $_payload): mixed => $handler(),
        );
    }

    /** @param list<float> $points */
    private static function validateSnapPoints(array $points): array
    {
        if ($points === [] || count($points) > 16) {
            throw new InvalidArgumentException(
                'Bottom Sheet requires between one and sixteen snap points.',
            );
        }
        $normalized = [];
        foreach ($points as $point) {
            if (!is_float($point) && !is_int($point)) {
                throw new InvalidArgumentException('Bottom Sheet snap points must be numbers.');
            }
            $value = (float) $point;
            if (!is_finite($value) || $value <= 0.0 || $value > 1.0) {
                throw new InvalidArgumentException(
                    'Bottom Sheet snap points must be fractions greater than zero and at most one.',
                );
            }
            $normalized[] = $value;
        }
        sort($normalized, SORT_NUMERIC);
        if (count(array_unique($normalized, SORT_REGULAR)) !== count($normalized)) {
            throw new InvalidArgumentException('Bottom Sheet snap points must be unique.');
        }

        return $normalized;
    }

    /** @param list<float> $points */
    private static function encodeSnapPoints(array $points): string
    {
        $bytes = pack('v', count($points));
        foreach ($points as $point) {
            $bytes .= pack('e', $point);
        }

        return $bytes;
    }
}
