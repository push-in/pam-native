<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\NativeOperation;
use RuntimeException;

final class Http
{
    private const MAX_BODY_BYTES = 1_048_576;

    private function __construct()
    {
    }

    /** @param Closure(HttpResponse): void $callback */
    public static function get(string $url, Closure $callback): int
    {
        return self::request('GET', $url, $callback);
    }

    /**
     * @param Closure(HttpResponse): void $callback
     * @param array<string, string> $headers
     */
    public static function post(
        string $url,
        Closure $callback,
        array $headers = [],
        ?string $body = null,
        int $timeoutMs = 30_000,
    ): int {
        return self::request('POST', $url, $callback, $headers, $body, $timeoutMs);
    }

    /**
     * @param Closure(HttpResponse): void $callback
     * @param array<string, string> $headers
     */
    public static function put(
        string $url,
        Closure $callback,
        array $headers = [],
        ?string $body = null,
        int $timeoutMs = 30_000,
    ): int {
        return self::request('PUT', $url, $callback, $headers, $body, $timeoutMs);
    }

    /**
     * @param Closure(HttpResponse): void $callback
     * @param array<string, string> $headers
     */
    public static function patch(
        string $url,
        Closure $callback,
        array $headers = [],
        ?string $body = null,
        int $timeoutMs = 30_000,
    ): int {
        return self::request('PATCH', $url, $callback, $headers, $body, $timeoutMs);
    }

    /**
     * @param Closure(HttpResponse): void $callback
     * @param array<string, string> $headers
     */
    public static function delete(
        string $url,
        Closure $callback,
        array $headers = [],
        ?string $body = null,
        int $timeoutMs = 30_000,
    ): int {
        return self::request('DELETE', $url, $callback, $headers, $body, $timeoutMs);
    }

    /**
     * @param Closure(HttpResponse): void $callback
     * @param array<string, string> $headers
     */
    public static function request(
        string $method,
        string $url,
        Closure $callback,
        array $headers = [],
        ?string $body = null,
        int $timeoutMs = 30_000,
    ): int {
        $method = strtoupper(trim($method));
        if (!in_array($method, ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'], true)) {
            throw new RuntimeException("Unsupported HTTP method {$method}.");
        }
        if ($body !== null && strlen($body) > self::MAX_BODY_BYTES) {
            throw new RuntimeException('HTTP request body cannot exceed one MiB.');
        }
        if (count($headers) > 32) {
            throw new RuntimeException('HTTP requests support at most 32 headers.');
        }

        $normalizedHeaders = [];
        foreach ($headers as $name => $value) {
            if (
                !is_string($name)
                || !is_string($value)
                || preg_match('/^[A-Za-z0-9-]{1,64}$/', $name) !== 1
                || str_contains($value, "\r")
                || str_contains($value, "\n")
                || strlen($value) > 8_192
            ) {
                throw new RuntimeException('HTTP headers must use safe names and bounded single-line values.');
            }
            $normalizedHeaders[$name] = $value;
        }

        $payload = [
            'url' => $url,
            'method' => $method,
            'headers' => json_encode($normalizedHeaders, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES),
            'timeoutMs' => max(1_000, min(120_000, $timeoutMs)),
        ];
        if ($body !== null) {
            $payload['body'] = $body;
        }

        return Runtime::call(
            module: 'http',
            method: 'request',
            payload: Wire::map($payload),
            callback: self::responseCallback($callback),
        );
    }

    /**
     * @param Closure(HttpResponse): void $callback
     * @param array<string, mixed> $data
     * @param array<string, string> $headers
     */
    public static function json(
        string $method,
        string $url,
        array $data,
        Closure $callback,
        array $headers = [],
        int $timeoutMs = 30_000,
    ): int {
        return self::request(
            method: $method,
            url: $url,
            callback: $callback,
            headers: [
                'Accept' => 'application/json',
                'Content-Type' => 'application/json',
                ...$headers,
            ],
            body: json_encode($data, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES),
            timeoutMs: $timeoutMs,
        );
    }

    /** @param Closure(HttpResponse): void $callback */
    private static function responseCallback(Closure $callback): Closure
    {
        return static function (ModuleResultStatus $status, string $payload) use ($callback): void {
            if ($status === ModuleResultStatus::Failure) {
                throw new RuntimeException($payload);
            }

            $values = Wire::decodeMap($payload);
            $callback(new HttpResponse(
                statusCode: (int) ($values['statusCode'] ?? 0),
                body: (string) ($values['body'] ?? ''),
            ));
        };
    }
}
