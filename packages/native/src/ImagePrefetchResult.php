<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class ImagePrefetchResult
{
    public function __construct(
        public int $succeeded,
        public int $failed,
        public int $bytes,
    ) {
    }

    public function complete(): bool
    {
        return $this->failed === 0;
    }
}
