<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\Element;
use Pam\Native\ImageFit;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

final class Image extends Element
{
    public static function make(string $source): self
    {
        return (new self(NodeKind::Image))->withProperty(PropKey::Source, $source);
    }

    public function fit(ImageFit $fit): self
    {
        return $this->withProperty(PropKey::ImageFit, $fit->value);
    }

    public function tint(int $color): self
    {
        return $this->withProperty(PropKey::TintColor, $color);
    }
}
