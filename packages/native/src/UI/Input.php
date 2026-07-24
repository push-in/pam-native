<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\InputSyncMode;
use Pam\Native\KeyboardType;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\ReturnKeyType;
use Pam\Native\UI\Concerns\HasInputBehavior;

final class Input extends Element
{
    use HasInputBehavior;

    public static function make(string $value = ''): self
    {
        return (new self(NodeKind::Input))->withProperty(PropKey::Value, $value);
    }

    public function placeholder(string $placeholder): self
    {
        return $this->withProperty(PropKey::Placeholder, $placeholder);
    }

    public function onChange(Closure $handler): self
    {
        return $this->withEvent(EventKind::Change, $handler);
    }

    public function onFocus(Closure $handler): self
    {
        return $this->withEvent(EventKind::Focus, $handler);
    }

    public function onBlur(Closure $handler): self
    {
        return $this->withEvent(EventKind::Blur, $handler);
    }

    public function onSubmit(Closure $handler): self
    {
        return $this->withEvent(EventKind::Submit, $handler);
    }

    public function nativeState(
        InputSyncMode $mode = InputSyncMode::Debounced,
        int $debounceMs = 48,
    ): self {
        return $this
            ->withProperty(PropKey::InputSyncMode, $mode->value)
            ->withProperty(PropKey::InputDebounceMs, max(0, $debounceMs));
    }

    public function keyboard(KeyboardType $type): self
    {
        return $this->withProperty(PropKey::KeyboardType, $type->value);
    }

    public function multiline(bool $multiline = true): self
    {
        return $this->withProperty(PropKey::Multiline, $multiline);
    }

    public function secure(bool $secure = true): self
    {
        return $this->withProperty(PropKey::Secure, $secure);
    }

    public function maxLength(int $length): self
    {
        return $this->withProperty(PropKey::MaxLength, max(0, $length));
    }

    public function autoFocus(bool $autoFocus = true): self
    {
        return $this->withProperty(PropKey::AutoFocus, $autoFocus);
    }

    public function returnKey(ReturnKeyType $type): self
    {
        return $this->withProperty(PropKey::ReturnKeyType, $type->value);
    }
}
