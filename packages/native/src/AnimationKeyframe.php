<?php

declare(strict_types=1);

namespace Pam\Native;

use InvalidArgumentException;

final readonly class AnimationKeyframe
{
    public function __construct(
        public float $offset,
        public ?float $opacity = null,
        public ?float $translationX = null,
        public ?float $translationXPercent = null,
        public ?float $translationY = null,
        public ?float $scaleX = null,
        public ?float $scaleY = null,
        public ?float $rotation = null,
    ) {
        if (!is_finite($offset) || $offset < 0 || $offset > 1) {
            throw new InvalidArgumentException('Animation keyframe offset must be between zero and one.');
        }
    }
}
