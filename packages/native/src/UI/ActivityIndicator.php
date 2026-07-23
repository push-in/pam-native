<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

final class ActivityIndicator extends Element
{
    public static function make(bool $visible = true): self
    {
        return (new self(NodeKind::ActivityIndicator))
            ->withProperty(PropKey::Visible, $visible);
    }

    public function color(int $color): self
    {
        return $this->withProperty(PropKey::ProgressColor, $color);
    }
}
