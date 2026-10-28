<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use JsonException;

use function array_key_exists;
use function is_array;

/** Settled response of an `HttpClient` request. */
final readonly class Response
{
    /** @param array<string, string> $headers Lower-case header names. */
    public function __construct(
        public int $status,
        public array $headers,
        public string $body,
        public Request $request,
    ) {
    }

    /** @internal */
    public static function fromTransport(HttpResponse $response, Request $request): self
    {
        return new self($response->statusCode, $response->headers, $response->body, $request);
    }

    public function ok(): bool
    {
        return $this->status >= 200 && $this->status < 300;
    }

    public function header(string $name): ?string
    {
        return $this->headers[strtolower($name)] ?? null;
    }

    public function text(): string
    {
        return $this->body;
    }

    /**
     * Decoded JSON body (`null` for an empty body). `$key` reads a dot path,
     * e.g. `json('data.items')`.
     */
    public function json(?string $key = null, mixed $default = null): mixed
    {
        if ($this->body === '') return $key === null ? null : $default;
        try {
            $decoded = json_decode($this->body, true, 512, JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new HttpError('The response body is not valid JSON.', $this->request, $this, $error);
        }
        if ($key === null) return $decoded;
        foreach (explode('.', $key) as $segment) {
            if (!is_array($decoded) || !array_key_exists($segment, $decoded)) return $default;
            $decoded = $decoded[$segment];
        }

        return $decoded;
    }

    public function withBody(string $body): self
    {
        return new self($this->status, $this->headers, $body, $this->request);
    }
}
