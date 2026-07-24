<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

final class Toggle extends Element
{
    public static function make(bool $checked = false): self
    {
        return (new self(NodeKind::Switch))->withProperty(PropKey::Checked, $checked);
    }

    public function onToggle(Closure $handler): self
    {
        return $this->withEvent(EventKind::Toggle, $handler);
    }

    public function trackColors(int $off, int $on): self
    {
        return $this
            ->withProperty(PropKey::SwitchTrackColorFalse, $off)
            ->withProperty(PropKey::SwitchTrackColorTrue, $on);
    }

    public function thumbColor(int $color): self
    {
        return $this->withProperty(PropKey::SwitchThumbColor, $color);
    }
}
