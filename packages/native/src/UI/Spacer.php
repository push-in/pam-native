<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\Style;

final class Spacer extends Element
{
    public static function make(float $size = 8.0): self
    {
        return (new self(NodeKind::Spacer))->style(new Style(height: $size));
    }
}

