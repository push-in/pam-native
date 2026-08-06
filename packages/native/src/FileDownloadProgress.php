<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class FileDownloadProgress
{
    public function __construct(
        public int $bytesWritten,
        public int $totalBytes,
    ) {
    }

    public function fraction(): float
    {
        if ($this->totalBytes <= 0) {
            return 0.0;
        }

        return max(0.0, min(1.0, $this->bytesWritten / $this->totalBytes));
    }
}
