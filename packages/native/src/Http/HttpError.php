<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use RuntimeException;
use Throwable;

/**
 * Rejection reason of an `HttpClient` request: a transport failure (no
 * response: offline, DNS, TLS, timeout) or a response with status >= 400.
 */
final class HttpError extends RuntimeException
{
    public readonly int $status;

    public function __construct(
        string $message,
        public readonly Request $request,
        public readonly ?Response $response = null,
        ?Throwable $previous = null,
    ) {
        $this->status = $response?->status ?? 0;
        parent::__construct($message, $this->status, $previous);
    }

    /** No response arrived (offline, DNS, TLS, timeout or cancelled transport). */
    public function isNetworkError(): bool
    {
        return $this->response === null;
    }

    public function isTimeout(): bool
    {
        return $this->response === null && stripos($this->getMessage(), 'timeout') !== false
            || stripos($this->getMessage(), 'timed out') !== false;
    }

    public function isClientError(): bool
    {
        return $this->status >= 400 && $this->status < 500;
    }

    public function isServerError(): bool
    {
        return $this->status >= 500;
    }

    /** Decoded error body when the server answered with JSON. */
    public function json(?string $key = null, mixed $default = null): mixed
    {
        if ($this->response === null) return $default;
        try {
            return $this->response->json($key, $default);
        } catch (HttpError) {
            return $default;
        }
    }
}
