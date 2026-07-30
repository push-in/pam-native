<?php

declare(strict_types=1);

namespace Pam\Native;

final readonly class MediaAssetPage
{
    /** @param list<MediaAsset> $items */
    public function __construct(
        public array $items,
        public int $nextOffset,
        public bool $hasMore,
    ) {
    }
}
