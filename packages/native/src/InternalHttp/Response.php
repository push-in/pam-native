<?php

declare(strict_types=1);

namespace Pam\Native\InternalHttp;

final readonly class Response
{
    /** @param array<string, string> $headers */
    public function __construct(public int $status, public string $body = '', public array $headers = [])
    {
        if ($status < 100 || $status > 599) {
            throw new \InvalidArgumentException('Internal response status is invalid.');
        }
    }

    /** @param array<string, mixed> $payload */
    public static function json(array $payload, int $status = 200): self
    {
        return new self($status, json_encode($payload, JSON_THROW_ON_ERROR), ['content-type' => 'application/json']);
    }
}
