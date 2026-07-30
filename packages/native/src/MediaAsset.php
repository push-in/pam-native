<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class MediaAsset
{
    public function __construct(
        public string $id,
        public string $uri,
        public string $name,
        public string $mimeType,
        public int $width,
        public int $height,
        public int $durationMs,
        public int $size,
        public int $createdAt,
        public int $modifiedAt,
        public string $albumId,
        public string $albumTitle,
        public bool $favorite,
    ) {
    }

    public function video(): bool
    {
        return str_starts_with($this->mimeType, 'video/');
    }
}
