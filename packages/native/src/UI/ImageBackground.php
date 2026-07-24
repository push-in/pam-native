<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;
use Pam\Native\UI\Concerns\HasImageBehavior;

final class ImageBackground extends Element
{
    use HasImageBehavior;

    public static function make(string $source, Renderable ...$children): self
    {
        return (new self(NodeKind::ImageBackground))
            ->withChildren($children)
            ->withProperty(PropKey::Source, $source);
    }
}
