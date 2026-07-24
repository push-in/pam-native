<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\KeyboardAvoidingBehavior;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class KeyboardAvoidingView extends Element
{
    public static function make(
        Renderable $content,
        KeyboardAvoidingBehavior $behavior = KeyboardAvoidingBehavior::Resize,
    ): self {
        return (new self(NodeKind::KeyboardAvoidingView))
            ->withChildren([$content])
            ->withProperty(PropKey::KeyboardBehavior, $behavior->value);
    }

    public function verticalOffset(float $offset): self
    {
        return $this->withProperty(PropKey::KeyboardVerticalOffset, $offset);
    }

    public function avoidingEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::KeyboardAvoidingEnabled, $enabled);
    }
}
