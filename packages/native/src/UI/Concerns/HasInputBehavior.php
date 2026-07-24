<?php

declare(strict_types=1);

namespace Pam\Native\UI\Concerns;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\InputAutoCapitalize;
use Pam\Native\InputAutofillImportance;
use Pam\Native\InputContentSizeEvent;
use Pam\Native\InputKeyEvent;
use Pam\Native\InputMode;
use Pam\Native\InputSelectionEvent;
use Pam\Native\InputSubmitBehavior;
use Pam\Native\InputTextAlignVertical;
use Pam\Native\PropKey;

/** @phpstan-require-extends Element */
trait HasInputBehavior
{
    public function editable(bool $editable = true): static
    {
        return $this->withProperty(PropKey::InputEditable, $editable);
    }

    public function autoCorrect(bool $enabled = true): static
    {
        return $this->withProperty(PropKey::InputAutoCorrect, $enabled);
    }

    public function autoCapitalize(InputAutoCapitalize $mode): static
    {
        return $this->withProperty(PropKey::InputAutoCapitalize, $mode->value);
    }

    public function caretHidden(bool $hidden = true): static
    {
        return $this->withProperty(PropKey::InputCaretHidden, $hidden);
    }

    public function contextMenuHidden(bool $hidden = true): static
    {
        return $this->withProperty(PropKey::InputContextMenuHidden, $hidden);
    }

    public function cursorColor(int $color): static
    {
        return $this->withProperty(PropKey::InputCursorColor, $color);
    }

    public function disableFullscreenUi(bool $disabled = true): static
    {
        return $this->withProperty(
            PropKey::InputDisableFullscreenUi,
            $disabled,
        );
    }

    public function autofillImportance(
        InputAutofillImportance $importance,
    ): static {
        return $this->withProperty(
            PropKey::InputAutofillImportance,
            $importance->value,
        );
    }

    public function inputMode(InputMode $mode): static
    {
        return $this->withProperty(PropKey::InputMode, $mode->value);
    }

    public function minLines(int $lines): static
    {
        return $this->withProperty(PropKey::InputMinLines, max(1, $lines));
    }

    public function selectTextOnFocus(bool $enabled = true): static
    {
        return $this->withProperty(PropKey::InputSelectTextOnFocus, $enabled);
    }

    public function selection(int $start, ?int $end = null): static
    {
        $start = max(0, $start);

        return $this
            ->withProperty(PropKey::InputSelectionStart, $start)
            ->withProperty(
                PropKey::InputSelectionEnd,
                max($start, $end ?? $start),
            );
    }

    public function showSoftInputOnFocus(bool $enabled = true): static
    {
        return $this->withProperty(
            PropKey::InputShowSoftInputOnFocus,
            $enabled,
        );
    }

    public function submitBehavior(InputSubmitBehavior $behavior): static
    {
        return $this->withProperty(
            PropKey::InputSubmitBehavior,
            $behavior->value,
        );
    }

    public function textAlignVertical(
        InputTextAlignVertical $alignment,
    ): static {
        return $this->withProperty(
            PropKey::InputTextAlignVertical,
            $alignment->value,
        );
    }

    public function returnKeyLabel(string $label): static
    {
        return $this->withProperty(
            PropKey::InputReturnKeyLabel,
            substr($label, 0, 64),
        );
    }

    public function scrollEnabled(bool $enabled = true): static
    {
        return $this->withProperty(PropKey::InputScrollEnabled, $enabled);
    }

    public function underlineColor(int $color): static
    {
        return $this->withProperty(PropKey::InputUnderlineColor, $color);
    }

    public function onEndEditing(Closure $handler): static
    {
        return $this->withEvent(EventKind::InputEndEditing, $handler);
    }

    /** @param Closure(InputSelectionEvent): void $handler */
    public function onSelectionChange(Closure $handler): static
    {
        return $this->withEvent(
            EventKind::InputSelectionChange,
            static function (string $payload) use ($handler): void {
                $handler(InputSelectionEvent::fromPayload($payload));
            },
        );
    }

    /** @param Closure(InputContentSizeEvent): void $handler */
    public function onContentSizeChange(Closure $handler): static
    {
        return $this->withEvent(
            EventKind::InputContentSizeChange,
            static function (string $payload) use ($handler): void {
                $handler(InputContentSizeEvent::fromPayload($payload));
            },
        );
    }

    /** @param Closure(InputKeyEvent): void $handler */
    public function onKeyPress(Closure $handler): static
    {
        return $this->withEvent(
            EventKind::InputKeyPress,
            static function (string $payload) use ($handler): void {
                $handler(InputKeyEvent::fromPayload($payload));
            },
        );
    }
}
