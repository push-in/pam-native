<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Pam\Native\ActivityIndicatorSize;
use Pam\Native\Element;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

final class ActivityIndicator extends Element
{
    public static function make(bool $animating = true): self
    {
        return (new self(NodeKind::ActivityIndicator))
            ->withProperty(PropKey::Visible, true)
            ->withProperty(PropKey::ActivityAnimating, $animating)
            ->withProperty(PropKey::ActivityHidesWhenStopped, true)
            ->size(ActivityIndicatorSize::Small);
    }

    public function animating(bool $animating = true): self
    {
        return $this->withProperty(PropKey::ActivityAnimating, $animating);
    }

    public function hidesWhenStopped(bool $hide = true): self
    {
        return $this->withProperty(PropKey::ActivityHidesWhenStopped, $hide);
    }

    public function size(ActivityIndicatorSize|float $size): self
    {
        $pixels = $size instanceof ActivityIndicatorSize
            ? $size->densityIndependentPixels()
            : max(1.0, $size);

        return $this
            ->withProperty(PropKey::ActivitySize, $pixels)
            ->withProperty(PropKey::Width, $pixels)
            ->withProperty(PropKey::Height, $pixels);
    }

    public function color(int $color): self
    {
        return $this->withProperty(PropKey::ProgressColor, $color);
    }
}
