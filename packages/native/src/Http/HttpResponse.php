<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use DateTimeImmutable;
use DateTimeInterface;

final readonly class HttpResponse
{
    /** @var array<string, string> Lower-case header names. */
    public array $headers;

    /** @param array<string, string> $headers */
    public function __construct(
        public int $statusCode,
        public string $body,
        public string $error = '',
        array $headers = [],
    ) {
        $normalized = [];
        foreach ($headers as $name => $value) {
            if (is_string($name) && is_scalar($value)) {
                $normalized[strtolower($name)] = (string) $value;
            }
        }
        $this->headers = $normalized;
    }

    /** @internal Builds a response from the native wire map. */
    public static function fromNative(array $values): self
    {
        $headers = json_decode((string) ($values['headers'] ?? '{}'), true);

        return new self(
            statusCode: (int) ($values['statusCode'] ?? 0),
            body: (string) ($values['body'] ?? ''),
            headers: is_array($headers) ? $headers : [],
        );
    }

    public function successful(): bool
    {
        return $this->statusCode >= 200 && $this->statusCode < 300;
    }

    public function transportFailed(): bool
    {
        return $this->statusCode === 0 && $this->error !== '';
    }

    /** Case-insensitive header lookup. */
    public function header(string $name): ?string
    {
        return $this->headers[strtolower($name)] ?? null;
    }

    /** Server clock from the Date header, e.g. to correct client clock skew. */
    public function date(): ?DateTimeImmutable
    {
        $value = $this->header('date');
        if ($value === null) {
            return null;
        }
        $date = DateTimeImmutable::createFromFormat(DateTimeInterface::RFC7231, $value);

        return $date === false ? null : $date;
    }
}
