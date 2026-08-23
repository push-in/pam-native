<?php

declare(strict_types=1);

namespace Pam\Native\InternalHttp;

final readonly class Request
{
    /** @param array<string, string> $headers */
    public function __construct(
        public string $method,
        public string $path,
        public array $headers = [],
        public string $body = '',
    ) {
        if (preg_match('/^[A-Z]{3,12}$/D', $method) !== 1 || !str_starts_with($path, '/') || strlen($body) > 16_777_216) {
            throw new \InvalidArgumentException('Internal request is invalid or exceeds 16 MiB.');
        }
    }
}
