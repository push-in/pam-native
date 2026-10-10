<?php

declare(strict_types=1);

namespace Pam\Native;

/** A view rendered by ViewCapture: the private image file and its pixel size. */
final readonly class CapturedImage
{
    public function __construct(
        public FileReference $file,
        public int $width,
        public int $height,
    ) {
    }

    /** Width / height of the image (1.0 for a degenerate capture). */
    public function aspectRatio(): float
    {
        return $this->height > 0 ? $this->width / $this->height : 1.0;
    }
}
