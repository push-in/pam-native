<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use Closure;
use InvalidArgumentException;
use Pam\Native\System\Files;

use function count;
use function in_array;
use function is_bool;
use function strlen;

/**
 * Fluent multipart/form-data upload streamed natively from private files.
 *
 *     Http::multipart('https://api.example.com/media')
 *         ->file('media', 'uploads/photo.jpg', 'image/jpeg')
 *         ->field('caption', 'Hello')
 *         ->bearer($token)
 *         ->onProgress(fn (TransferProgress $p) => $this->progress = $p->fraction())
 *         ->send(fn (HttpResponse $response) => ...);
 */
final class MultipartRequest
{
    private const int FIELD = 1;
    private const int FILE = 2;

    private string $method = 'POST';

    /** @var list<array{type: int, name: string, value?: string, path?: string, mimeType?: string, filename?: string}> */
    private array $parts = [];

    /** @var array<string, string> */
    private array $headers = [];

    private int $timeoutMs = 60_000;

    private ?Closure $progress = null;

    private ?OutboundTraceContext $trace = null;

    public function __construct(private readonly string $url)
    {
        Http::assertTransferUrl($url);
    }

    public function method(string $method): self
    {
        $method = strtoupper(trim($method));
        if (!in_array($method, ['POST', 'PUT', 'PATCH'], true)) {
            throw new InvalidArgumentException('Multipart uploads support POST, PUT or PATCH.');
        }
        $this->method = $method;

        return $this;
    }

    public function field(string $name, string|int|float|bool $value): self
    {
        $value = is_bool($value) ? ($value ? '1' : '0') : (string) $value;
        if (strlen($value) > 1_048_576) {
            throw new InvalidArgumentException('Multipart fields cannot exceed one MiB.');
        }
        $this->parts[] = ['type' => self::FIELD, 'name' => self::name($name), 'value' => $value];

        return $this->bounded();
    }

    /** @param array<string, string|int|float|bool> $fields */
    public function fields(array $fields): self
    {
        foreach ($fields as $name => $value) {
            $this->field((string) $name, $value);
        }

        return $this;
    }

    /** Attaches a private sandbox file; its bytes never enter PHP. */
    public function file(string $field, string $path, ?string $mimeType = null, ?string $filename = null): self
    {
        $mimeType = trim((string) $mimeType);
        if ($mimeType !== '' && preg_match('#^[a-z0-9!\#$&^_.+-]{1,127}/[a-z0-9!\#$&^_.+-]{1,127}$#iD', $mimeType) !== 1) {
            throw new InvalidArgumentException('Multipart MIME type is invalid.');
        }
        $filename = $filename ?? basename($path);
        if ($filename === '' || strlen($filename) > 255 || preg_match('#[\x00\r\n/\\\\]#', $filename) === 1) {
            throw new InvalidArgumentException('Multipart filename is invalid.');
        }
        $this->parts[] = [
            'type' => self::FILE,
            'name' => self::name($field),
            'path' => Files::privatePath($path, 'Multipart file'),
            'mimeType' => $mimeType,
            'filename' => $filename,
        ];

        return $this->bounded();
    }

    public function header(string $name, string $value): self
    {
        if (strtolower($name) === 'content-type') {
            throw new InvalidArgumentException('Multipart uploads own the Content-Type boundary.');
        }
        $this->headers[$name] = $value;
        Http::normalizeHeaders($this->headers, transport: true);

        return $this;
    }

    /** @param array<string, string> $headers */
    public function headers(array $headers): self
    {
        foreach ($headers as $name => $value) {
            $this->header((string) $name, $value);
        }

        return $this;
    }

    public function bearer(string $token): self
    {
        if ($token === '') {
            throw new InvalidArgumentException('Bearer token cannot be empty.');
        }

        return $this->header('Authorization', 'Bearer '.$token);
    }

    public function timeout(int $milliseconds): self
    {
        $this->timeoutMs = max(1_000, min(600_000, $milliseconds));

        return $this;
    }

    public function trace(OutboundTraceContext $trace): self
    {
        if (!$trace->allows($this->url)) {
            throw new InvalidArgumentException('Trace context origin does not match the HTTP request origin.');
        }
        $this->trace = $trace;

        return $this;
    }

    /** @param Closure(TransferProgress): void $progress */
    public function onProgress(Closure $progress): self
    {
        $this->progress = $progress;

        return $this;
    }

    /** @param Closure(HttpResponse): void $callback */
    public function send(Closure $callback): HttpTransfer
    {
        if ($this->parts === []) {
            throw new InvalidArgumentException('Multipart uploads require at least one field or file.');
        }
        $payload = [
            'kind' => 1,
            'url' => $this->url,
            'method' => $this->method,
            'headers' => json_encode((object) $this->headers, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES),
            'parts' => json_encode($this->parts, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES | JSON_UNESCAPED_UNICODE),
            'timeoutMs' => $this->timeoutMs,
        ];
        if ($this->trace !== null) {
            $payload['traceparent'] = $this->trace->traceparent;
            $payload['traceOrigin'] = $this->trace->origin;
        }

        return HttpTransfers::start($payload, $callback, $this->progress);
    }

    /** @return list<array<string, int|string>> @internal */
    public function parts(): array
    {
        return $this->parts;
    }

    private function bounded(): self
    {
        if (count($this->parts) > 64) {
            throw new InvalidArgumentException('Multipart uploads support at most 64 parts.');
        }

        return $this;
    }

    private static function name(string $name): string
    {
        if ($name === '' || strlen($name) > 256 || preg_match('/[\x00\r\n]/', $name) === 1) {
            throw new InvalidArgumentException('Multipart field names must be single-line and non-empty.');
        }

        return $name;
    }
}
