<?php

declare(strict_types=1);

namespace Pam\Native\UI\Concerns;

use Closure;
use InvalidArgumentException;
use Pam\Native\EventKind;
use Pam\Native\Internal\Wire;
use Pam\Native\MediaCacheEvent;
use Pam\Native\MediaPriority;
use Pam\Native\PropKey;

trait HasMediaCacheBehavior
{
    public function cacheKey(string $key): static
    {
        if (preg_match('/^[A-Za-z0-9][A-Za-z0-9:._-]{0,255}$/D', $key) !== 1) {
            throw new InvalidArgumentException('Media cache keys must be safe stable identifiers.');
        }

        return $this->withProperty(PropKey::MediaCacheKey, $key);
    }

    public function maxAge(int $milliseconds): static
    {
        return $this->withProperty(
            PropKey::MediaCacheMaxAgeMs,
            max(0, min(31_536_000_000, $milliseconds)),
        );
    }

    /** @param list<string> $tags */
    public function cacheTags(array $tags): static
    {
        if (count($tags) > 32) {
            throw new InvalidArgumentException('Media entries support at most 32 cache tags.');
        }
        $safe = [];
        foreach ($tags as $tag) {
            if (preg_match('/^[A-Za-z0-9][A-Za-z0-9:._-]{0,127}$/D', $tag) !== 1) {
                throw new InvalidArgumentException('Media cache tags must be safe identifiers.');
            }
            $safe[] = $tag;
        }

        return $this->withProperty(PropKey::MediaCacheTags, implode("\n", array_unique($safe)));
    }

    public function pinOffline(bool $pinned = true): static
    {
        return $this->withProperty(PropKey::MediaCachePinOffline, $pinned);
    }

    public function priority(MediaPriority $priority): static
    {
        return $this->withProperty(PropKey::MediaPriority, $priority->value);
    }

    public function maxCacheSize(int $bytes): static
    {
        return $this->withProperty(
            PropKey::MediaCacheMaxBytes,
            max(0, min(10_737_418_240, $bytes)),
        );
    }

    public function checksum(string $sha256): static
    {
        if (preg_match('/^[a-fA-F0-9]{64}$/D', $sha256) !== 1) {
            throw new InvalidArgumentException('Media checksum must be SHA-256 hexadecimal.');
        }

        return $this->withProperty(PropKey::MediaCacheChecksum, strtolower($sha256));
    }

    /** @param Closure(MediaCacheEvent): void $handler */
    public function onCacheHit(Closure $handler): static
    {
        return $this->mediaCacheEvent(EventKind::MediaCacheHit, $handler);
    }

    /** @param Closure(MediaCacheEvent): void $handler */
    public function onCacheMiss(Closure $handler): static
    {
        return $this->mediaCacheEvent(EventKind::MediaCacheMiss, $handler);
    }

    /** @param Closure(MediaCacheEvent): void $handler */
    public function onCacheProgress(Closure $handler): static
    {
        return $this->mediaCacheEvent(EventKind::MediaCacheProgress, $handler);
    }

    /** @param Closure(MediaCacheEvent): void $handler */
    public function onCacheReady(Closure $handler): static
    {
        return $this->mediaCacheEvent(EventKind::MediaCacheReady, $handler);
    }

    private function mediaCacheEvent(EventKind $kind, Closure $handler): static
    {
        return $this->withEvent(
            $kind,
            static function (string $payload) use ($handler): void {
                $handler(MediaCacheEvent::fromPayload($payload));
            },
        );
    }
}
