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

    /**
     * Returns an opaque renderer source for the file inside the application sandbox.
     *
     * The native renderer resolves this URI without exposing an absolute device path.
     */
    public function uri(): string
    {
        $segments = array_map(
            static fn (string $segment): string => rawurlencode($segment),
            explode('/', str_replace('\\', '/', ltrim($this->path, '/'))),
        );

        return 'pam-file:///' . implode('/', $segments);
    }
}
