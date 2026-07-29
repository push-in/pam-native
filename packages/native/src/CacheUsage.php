<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class CacheUsage
{
    public function __construct(
        public int $fileCount,
        public int $imageBytes,
        public int $mediaBytes,
        public int $temporaryBytes,
        public int $totalBytes,
        public int $freedBytes = 0,
    ) {
    }
}
