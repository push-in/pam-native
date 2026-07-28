<?php

declare(strict_types=1);

namespace Pam\Native\UI\Concerns;

use Closure;
use InvalidArgumentException;
use Pam\Native\EventKind;
use Pam\Native\ImageCachePolicy;
use Pam\Native\MediaCachePolicy;
use Pam\Native\ImageErrorEvent;
use Pam\Native\ImageFit;
use Pam\Native\ImageLoadEvent;
use Pam\Native\ImageProgressEvent;
use Pam\Native\ImageResizeMethod;
use Pam\Native\PropKey;

trait HasImageBehavior
{
    use HasMediaCacheBehavior;
    public function fit(ImageFit $fit): static
    {
        return $this->withProperty(PropKey::ImageFit, $fit->value);
    }

    public function tint(int $color): static
    {
        return $this->withProperty(PropKey::TintColor, $color);
    }

    public function defaultSource(string $source): static
    {
        return $this->withProperty(PropKey::ImageDefaultSource, $source);
    }

    public function loadingIndicatorSource(string $source): static
    {
        return $this->withProperty(
            PropKey::ImageLoadingIndicatorSource,
            $source,
        );
    }

    public function fadeDuration(int $milliseconds): static
    {
        return $this->withProperty(
            PropKey::ImageFadeDurationMs,
            max(0, min(10_000, $milliseconds)),
        );
    }

    public function resizeMethod(ImageResizeMethod $method): static
    {
        return $this->withProperty(PropKey::ImageResizeMethod, $method->value);
    }

    public function resizeMultiplier(float $multiplier): static
    {
        return $this->withProperty(
            PropKey::ImageResizeMultiplier,
            max(0.1, min(8.0, $multiplier)),
        );
    }

    public function progressiveRendering(bool $enabled = true): static
    {
        return $this->withProperty(
            PropKey::ImageProgressiveRenderingEnabled,
            $enabled,
        );
    }

    public function cache(
        ImageCachePolicy|MediaCachePolicy $policy = MediaCachePolicy::MemoryAndDisk,
    ): static
    {
        return $policy instanceof ImageCachePolicy
            ? $this->withProperty(PropKey::ImageCachePolicy, $policy->value)
            : $this->withProperty(PropKey::MediaCachePolicy, $policy->value);
    }

    public function resize(int $width, int $height): static
    {
        return $this
            ->withProperty(PropKey::MediaResizeWidth, max(1, min(8192, $width)))
            ->withProperty(PropKey::MediaResizeHeight, max(1, min(8192, $height)));
    }

    public function thumbnail(string $source): static
    {
        return $this->withProperty(PropKey::MediaThumbnailSource, $source);
    }

    public function overlayColor(int $color): static
    {
        return $this->withProperty(PropKey::ImageOverlayColor, $color);
    }

    public function sourceSet(string $sourceSet): static
    {
        return $this->withProperty(PropKey::ImageSourceSet, $sourceSet);
    }

    /**
     * @param array<string, string> $headers
     */
    public function headers(array $headers): static
    {
        if (count($headers) > 32) {
            throw new InvalidArgumentException(
                'Image requests support at most 32 headers.',
            );
        }
        $lines = [];
        foreach ($headers as $name => $value) {
            if (
                preg_match('/^[A-Za-z0-9!#$%&\'*+.^_`|~-]{1,64}$/', $name)
                    !== 1
                || str_contains($value, "\r")
                || str_contains($value, "\n")
                || strlen($value) > 4_096
            ) {
                throw new InvalidArgumentException(
                    'Image request headers must use safe names and bounded values.',
                );
            }
            $lines[] = $name.':'.$value;
        }

        return $this->withProperty(
            PropKey::ImageRequestHeaders,
            implode("\n", $lines),
        );
    }

    /** @param Closure(): void $handler */
    public function onLoadStart(Closure $handler): static
    {
        return $this->withEvent(
            EventKind::ImageLoadStart,
            static function (string $_payload) use ($handler): void {
                $handler();
            },
        );
    }

    /** @param Closure(ImageProgressEvent): void $handler */
    public function onProgress(Closure $handler): static
    {
        return $this->withEvent(
            EventKind::ImageProgress,
            static function (string $payload) use ($handler): void {
                $handler(ImageProgressEvent::fromPayload($payload));
            },
        );
    }

    /** @param Closure(ImageLoadEvent): void $handler */
    public function onLoad(Closure $handler): static
    {
        return $this->withEvent(
            EventKind::ImageLoad,
            static function (string $payload) use ($handler): void {
                $handler(ImageLoadEvent::fromPayload($payload));
            },
        );
    }

    /** @param Closure(ImageErrorEvent): void $handler */
    public function onError(Closure $handler): static
    {
        return $this->withEvent(
            EventKind::ImageError,
            static function (string $payload) use ($handler): void {
                $handler(ImageErrorEvent::fromPayload($payload));
            },
        );
    }

    /** @param Closure(): void $handler */
    public function onLoadEnd(Closure $handler): static
    {
        return $this->withEvent(
            EventKind::ImageLoadEnd,
            static function (string $_payload) use ($handler): void {
                $handler();
            },
        );
    }
}
