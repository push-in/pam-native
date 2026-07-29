<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class AudioRecording
{
    public function __construct(
        public string $uri,
        public string $fileName,
        public string $mimeType,
        public int $durationMs,
        public int $size,
    ) {
    }
}
