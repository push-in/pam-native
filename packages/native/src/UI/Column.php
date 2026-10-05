<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\Renderable;

final class Column extends Element
{
    public static function make(Renderable|false|null ...$children): self
    {
        return (new self(NodeKind::Column))->withChildren($children);
    }
}
