<?php

declare(strict_types=1);

namespace Pam\Native\Http;

use InvalidArgumentException;

final readonly class OutboundTraceContext
{
    public string $origin;

    public function __construct(
        public string $traceparent,
        string $origin,
    ) {
        if (preg_match(
            '/^00-(?!0{32})[0-9a-f]{32}-(?!0{16})[0-9a-f]{16}-[0-9a-f]{2}$/D',
            $traceparent,
        ) !== 1) {
            throw new InvalidArgumentException('Invalid W3C version 00 traceparent.');
        }

        $parts = parse_url($origin);
        if (
            !is_array($parts)
            || strtolower((string) ($parts['scheme'] ?? '')) !== 'https'
            || !isset($parts['host'])
            || isset($parts['user'])
            || isset($parts['pass'])
            || isset($parts['query'])
            || isset($parts['fragment'])
            || !in_array($parts['path'] ?? '', ['', '/'], true)
        ) {
            throw new InvalidArgumentException('Trace propagation requires an exact HTTPS origin.');
        }

        $host = strtolower((string) $parts['host']);
        $host = str_contains($host, ':') ? '['.$host.']' : $host;
        $port = isset($parts['port']) && $parts['port'] !== 443 ? ':'.$parts['port'] : '';
        $this->origin = 'https://'.$host.$port;
    }

    public function allows(string $url): bool
    {
        $parts = parse_url($url);
        if (!is_array($parts) || strtolower((string) ($parts['scheme'] ?? '')) !== 'https') {
            return false;
        }
        $host = strtolower((string) ($parts['host'] ?? ''));
        $host = str_contains($host, ':') ? '['.$host.']' : $host;
        $port = isset($parts['port']) && $parts['port'] !== 443 ? ':'.$parts['port'] : '';

        return $host !== '' && 'https://'.$host.$port === $this->origin;
    }
}
