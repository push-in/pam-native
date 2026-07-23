<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\ImageFit;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\Renderable;

final class ImageBackground extends Element
{
    public static function make(string $source, Renderable ...$children): self
    {
        return (new self(NodeKind::ImageBackground))
            ->withChildren($children)
            ->withProperty(PropKey::Source, $source);
    }

    public function fit(ImageFit $fit): self
    {
        return $this->withProperty(PropKey::ImageFit, $fit->value);
    }
}
