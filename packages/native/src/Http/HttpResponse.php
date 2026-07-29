<?php

declare(strict_types=1);

namespace Pam\Native\Http;

final readonly class HttpResponse
{
    public function __construct(
        public int $statusCode,
        public string $body,
        public string $error = '',
    ) {
    }

    public function successful(): bool
    {
        return $this->statusCode >= 200 && $this->statusCode < 300;
    }

    public function transportFailed(): bool
    {
        return $this->statusCode === 0 && $this->error !== '';
    }
}
