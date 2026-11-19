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
        /** Files copied into the sandbox by pickers and imports (filesDir/pam-files/imports), part of totalBytes. */
        public int $importBytes = 0,
    ) {
    }
}
