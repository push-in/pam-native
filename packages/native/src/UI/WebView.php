<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\Internal\Wire;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

final class WebView extends Element
{
    public static function make(string $source): self
    {
        self::validateSource($source);

        return (new self(NodeKind::WebView))
            ->withProperty(PropKey::WebViewSource, $source)
            ->withProperty(PropKey::WebViewJavaScriptEnabled, true)
            ->withProperty(PropKey::WebViewDomStorageEnabled, true)
            ->withProperty(PropKey::WebViewAllowsInlineMedia, true);
    }

    public function javaScriptEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::WebViewJavaScriptEnabled, $enabled);
    }

    public function domStorageEnabled(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::WebViewDomStorageEnabled, $enabled);
    }

    public function userAgent(?string $userAgent): self
    {
        return $this->withProperty(PropKey::WebViewUserAgent, $userAgent ?? '');
    }

    public function injectedJavaScript(string $script): self
    {
        if (strlen($script) > 1_048_576) {
            throw new InvalidArgumentException('Injected JavaScript exceeds one MiB.');
        }
        return $this->withProperty(PropKey::WebViewInjectedJavaScript, $script);
    }

    public function allowsInlineMedia(bool $allows = true): self
    {
        return $this->withProperty(PropKey::WebViewAllowsInlineMedia, $allows);
    }

    /** @param list<string> $hosts */
    public function allowedHosts(array $hosts): self
    {
        $normalized = [];
        foreach ($hosts as $host) {
            if (!is_string($host) || preg_match('/^[a-z0-9.-]{1,253}$/i', $host) !== 1) {
                throw new InvalidArgumentException('WebView allowed hosts must be valid host names.');
            }
            $normalized[] = strtolower($host);
        }
        return $this->withProperty(
            PropKey::WebViewAllowedHosts,
            implode("\n", array_values(array_unique($normalized))),
        );
    }

    public function onLoad(Closure $handler): self
    {
        return $this->withEvent(EventKind::WebViewLoad, $handler);
    }

    /** @param Closure(string): void $handler */
    public function onError(Closure $handler): self
    {
        return $this->withEvent(
            EventKind::WebViewError,
            static fn (string $payload): mixed => $handler(
                (string) (Wire::decodeMap($payload)['message'] ?? ''),
            ),
        );
    }

    /** @param Closure(string): void $handler */
    public function onMessage(Closure $handler): self
    {
        return $this->withEvent(
            EventKind::WebViewMessage,
            static fn (string $payload): mixed => $handler(
                (string) (Wire::decodeMap($payload)['message'] ?? ''),
            ),
        );
    }

    private static function validateSource(string $source): void
    {
        if ($source === '' || strlen($source) > 1_048_576) {
            throw new InvalidArgumentException('WebView source must contain between 1 and 1048576 bytes.');
        }
        if (!str_starts_with(ltrim($source), '<')) {
            $scheme = strtolower((string) parse_url($source, PHP_URL_SCHEME));
            if (!in_array($scheme, ['https', 'http', 'file'], true)) {
                throw new InvalidArgumentException('WebView URL must use https, http or file.');
            }
        }
    }
}
