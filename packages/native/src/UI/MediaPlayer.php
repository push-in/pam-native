<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\EventKind;
use Pam\Native\ImageFit;
use Pam\Native\Internal\Wire;
use Pam\Native\MediaType;
use Pam\Native\MediaCachePolicy;
use Pam\Native\UI\Concerns\HasMediaCacheBehavior;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;

final class MediaPlayer extends Element
{
    use HasMediaCacheBehavior;
    public static function make(string $source, MediaType $type = MediaType::Video): self
    {
        if ($source === '' || strlen($source) > 8192) {
            throw new InvalidArgumentException('Media source is invalid.');
        }

        return (new self(NodeKind::Media))
            ->withProperty(PropKey::MediaSource, $source)
            ->withProperty(PropKey::MediaType, $type->value)
            ->withProperty(PropKey::MediaAutoPlay, false)
            ->withProperty(PropKey::MediaControls, true)
            ->withProperty(PropKey::MediaLoop, false)
            ->withProperty(PropKey::MediaMuted, false)
            ->withProperty(PropKey::MediaVolume, 1.0)
            ->withProperty(PropKey::MediaCurrentTime, 0.0)
            ->withProperty(PropKey::MediaPlaybackRate, 1.0);
    }

    public function autoPlay(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::MediaAutoPlay, $enabled);
    }

    public function controls(bool $visible = true): self
    {
        return $this->withProperty(PropKey::MediaControls, $visible);
    }

    public function loop(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::MediaLoop, $enabled);
    }

    public function muted(bool $muted = true): self
    {
        return $this->withProperty(PropKey::MediaMuted, $muted);
    }

    public function volume(float $volume): self
    {
        return $this->withProperty(PropKey::MediaVolume, max(0.0, min(1.0, $volume)));
    }

    public function currentTime(float $seconds): self
    {
        return $this->withProperty(PropKey::MediaCurrentTime, max(0.0, $seconds));
    }

    public function playbackRate(float $rate): self
    {
        return $this->withProperty(PropKey::MediaPlaybackRate, max(0.25, min(4.0, $rate)));
    }

    public function fit(ImageFit $fit): self
    {
        return $this->withProperty(PropKey::ImageFit, $fit->value);
    }

    public function cache(
        MediaCachePolicy $policy = MediaCachePolicy::Disk,
    ): self {
        return $this->withProperty(PropKey::MediaCachePolicy, $policy->value);
    }

    public function streamingCache(bool $enabled = true): self
    {
        return $this->withProperty(PropKey::MediaCacheStreaming, $enabled);
    }

    public function preloadSeconds(int $seconds): self
    {
        return $this->withProperty(
            PropKey::MediaCachePreloadSeconds,
            max(0, min(300, $seconds)),
        );
    }

    public function downloadWhilePlaying(bool $enabled = true): self
    {
        return $this->withProperty(
            PropKey::MediaCacheDownloadWhilePlaying,
            $enabled,
        );
    }

    public function thumbnail(string $source): self
    {
        return $this->withProperty(PropKey::MediaThumbnailSource, $source);
    }

    public function onReady(Closure $handler): self
    {
        return $this->withEvent(EventKind::MediaReady, $handler);
    }

    /** @param Closure(float, float): void $handler */
    public function onProgress(Closure $handler): self
    {
        return $this->withEvent(
            EventKind::MediaProgress,
            static function (string $payload) use ($handler): mixed {
                $values = Wire::decodeMap($payload);
                return $handler(
                    (float) ($values['currentTime'] ?? 0.0),
                    (float) ($values['duration'] ?? 0.0),
                );
            },
        );
    }

    public function onEnd(Closure $handler): self
    {
        return $this->withEvent(EventKind::MediaEnd, $handler);
    }

    /** @param Closure(string): void $handler */
    public function onError(Closure $handler): self
    {
        return $this->withEvent(
            EventKind::MediaError,
            static fn (string $payload): mixed => $handler(
                (string) (Wire::decodeMap($payload)['message'] ?? ''),
            ),
        );
    }
}
