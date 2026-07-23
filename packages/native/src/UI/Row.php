<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\Renderable;

final class Row extends Element
{
    public static function make(Renderable ...$children): self
    {
        return (new self(NodeKind::Row))->withChildren($children);
    }
}
