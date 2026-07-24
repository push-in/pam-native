<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\UI\Concerns\HasImageBehavior;

final class Image extends Element
{
    use HasImageBehavior;

    public static function make(string $source): self
    {
        return (new self(NodeKind::Image))->withProperty(PropKey::Source, $source);
    }
}
