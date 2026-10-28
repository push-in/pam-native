<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use InvalidArgumentException;

use function in_array;
use function is_array;
use function is_scalar;
use function strtoupper;

/**
 * Immutable outgoing request seen by `onRequest` interceptors. Every `with*`
 * returns a copy, so an interceptor can never leak a header into another
 * request.
 */
final readonly class Request
{
    /** @var array<string, string> */
    public array $headers;
    /** @var array<string, string|int|float|bool|null> */
    public array $query;

    /**
     * @param array<string, string> $headers
     * @param array<string, string|int|float|bool|null> $query
     */
    public function __construct(
        public string $method,
        public string $url,
        array $headers = [],
        public ?string $body = null,
        array $query = [],
        public int $timeoutMs = 30_000,
        public int $attempt = 1,
        /** Free-form values interceptors share (e.g. ['skipAuth' => true]). */
        public array $context = [],
    ) {
        if (!in_array($method, ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'], true)) {
            throw new InvalidArgumentException("Unsupported HTTP method {$method}.");
        }
        $this->headers = $headers;
        $this->query = $query;
    }

    public function withMethod(string $method): self
    {
        return $this->copy(method: strtoupper(trim($method)));
    }

    public function withUrl(string $url): self
    {
        return $this->copy(url: $url);
    }

    public function withHeader(string $name, string $value): self
    {
        $headers = $this->withoutHeaderNamed($name);
        $headers[$name] = $value;

        return $this->copy(headers: $headers);
    }

    /** @param array<string, string> $headers */
    public function withHeaders(array $headers): self
    {
        $copy = $this;
        foreach ($headers as $name => $value) $copy = $copy->withHeader($name, $value);

        return $copy;
    }

    public function withoutHeader(string $name): self
    {
        return $this->copy(headers: $this->withoutHeaderNamed($name));
    }

    public function withToken(string $token, string $type = 'Bearer'): self
    {
        if ($token === '') return $this->withoutHeader('Authorization');

        return $this->withHeader('Authorization', trim($type.' '.$token));
    }

    /** @param array<string, string|int|float|bool|null> $query */
    public function withQuery(array $query, bool $merge = true): self
    {
        return $this->copy(query: $merge ? [...$this->query, ...$query] : $query);
    }

    public function withBody(?string $body, ?string $contentType = null): self
    {
        $copy = $this->copy(body: $body);

        return $contentType === null ? $copy : $copy->withHeader('Content-Type', $contentType);
    }

    public function withJson(mixed $data): self
    {
        return $this->withBody(
            json_encode($data, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE),
            'application/json',
        );
    }

    public function withTimeout(int $milliseconds): self
    {
        return $this->copy(timeoutMs: $milliseconds);
    }

    public function withContext(string $key, mixed $value): self
    {
        return $this->copy(context: [...$this->context, $key => $value]);
    }

    /** @internal */
    public function withAttempt(int $attempt): self
    {
        return $this->copy(attempt: $attempt);
    }

    public function header(string $name): ?string
    {
        foreach ($this->headers as $key => $value) {
            if (strcasecmp($key, $name) === 0) return $value;
        }

        return null;
    }

    public function context(string $key, mixed $default = null): mixed
    {
        return $this->context[$key] ?? $default;
    }

    /** Absolute URL with the query string appended. */
    public function fullUrl(): string
    {
        $query = [];
        foreach ($this->query as $key => $value) {
            if ($value === null) continue;
            $query[$key] = is_scalar($value) ? (is_bool($value) ? ($value ? '1' : '0') : (string) $value) : $value;
        }
        if ($query === []) return $this->url;
        $separator = str_contains($this->url, '?') ? '&' : '?';

        return $this->url.$separator.http_build_query($query, '', '&', PHP_QUERY_RFC3986);
    }

    public function idempotent(): bool
    {
        return in_array($this->method, ['GET', 'PUT', 'DELETE'], true);
    }

    /** @return array<string, string> */
    private function withoutHeaderNamed(string $name): array
    {
        $headers = $this->headers;
        foreach (array_keys($headers) as $key) {
            if (strcasecmp($key, $name) === 0) unset($headers[$key]);
        }

        return $headers;
    }

    /**
     * @param array<string, string>|null $headers
     * @param array<string, string|int|float|bool|null>|null $query
     * @param array<string, mixed>|null $context
     */
    private function copy(
        ?string $method = null,
        ?string $url = null,
        ?array $headers = null,
        string|false|null $body = false,
        ?array $query = null,
        ?int $timeoutMs = null,
        ?int $attempt = null,
        ?array $context = null,
    ): self {
        return new self(
            method: $method ?? $this->method,
            url: $url ?? $this->url,
            headers: is_array($headers) ? $headers : $this->headers,
            body: $body === false ? $this->body : $body,
            query: $query ?? $this->query,
            timeoutMs: $timeoutMs ?? $this->timeoutMs,
            attempt: $attempt ?? $this->attempt,
            context: $context ?? $this->context,
        );
    }
}
