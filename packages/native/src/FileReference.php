<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class FileReference
{
    public function __construct(
        public string $path,
        public string $name,
        public string $mimeType,
        public int $size,
    ) {
    }
}
