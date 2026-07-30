<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class MediaAlbum
{
    public function __construct(
        public string $id,
        public string $title,
        public int $count,
        public string $coverUri,
    ) {
    }
}
