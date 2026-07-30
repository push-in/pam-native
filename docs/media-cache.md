# Native media cache

Pam Native caches remote image, video, and audio files below the component API.
Downloads, hashing, disk I/O, cache-hit image decoding, request deduplication, and
eviction run away from the UI thread; native views are mutated only on their UI thread.

## Tags

```html
<Image
    source="https://cdn.example.com/avatar.webp"
    cache="stale-while-revalidate"
    cache-key="avatar:user-42:v3"
    cache-max-age="30d"
    cache-tags="avatar,user-42"
    cache-max-bytes="64mb"
    priority="visible"
    resize-width="512"
    resize-height="512"
    pin-offline
    on:cacheHit="avatarCacheHit"
    on:cacheMiss="avatarCacheMiss"
    on:cacheReady="avatarCacheReady"
/>

<MediaPlayer
    source="https://cdn.example.com/video.mp4"
    cache="disk"
    cache-key="video:release-42"
    cache-max-age="7d"
    cache-max-bytes="2gb"
    preload-seconds="12"
    streaming-cache
    download-while-playing
    pin-offline
    controls
/>
```

Durations accept `ms`, `s`, `m`, `h`, and `d`. Byte sizes accept `b`, `kb`, `mb`,
and `gb`. Cache keys and tags are restricted to stable, filesystem-safe identifiers;
the native cache hashes them before creating files.

## PHP API

```php
use Pam\Native\MediaCachePolicy;
use Pam\Native\MediaPriority;
use Pam\Native\UI\Image;
use Pam\Native\UI\MediaPlayer;

$avatar = Image::make($url)
    ->cache(MediaCachePolicy::StaleWhileRevalidate)
    ->cacheKey("avatar:{$userId}:v3")
    ->maxAge(30 * 24 * 60 * 60 * 1000)
    ->cacheTags(['avatar', "user:{$userId}"])
    ->priority(MediaPriority::Visible)
    ->maxCacheSize(64 * 1024 * 1024)
    ->resize(512, 512)
    ->pinOffline();

$video = MediaPlayer::make($url)
    ->cache(MediaCachePolicy::Disk)
    ->cacheKey("video:{$releaseId}")
    ->streamingCache()
    ->downloadWhilePlaying()
    ->preloadSeconds(12);
```

Available policies are `none`, `memory`, `disk`, `memory-and-disk`,
`cache-first`, `network-first`, `cache-only`, and
`stale-while-revalidate`. In PHP these are the sequential integer-backed
`MediaCachePolicy` cases.

## Events

Images and media players expose `onCacheHit`, `onCacheMiss`, `onCacheProgress`, and
`onCacheReady`. Their `MediaCacheEvent` contains the stable key, loaded bytes, total
bytes, and whether the result came from disk. Progress is throttled before crossing
the runtime boundary.

`cache-only` never falls back to the network. A miss follows the component's normal
error path. A SHA-256 value supplied with `checksum()` must match before a downloaded
file becomes visible in the cache.

## Inspecting and clearing application cache

Use the typed `Caches` facade from a settings or diagnostics screen. Both
operations run off the UI thread.

```php
use Pam\Native\CacheUsage;
use Pam\Native\System\Caches;

Caches::usage(static function (CacheUsage $usage): void {
    echo $usage->totalBytes;
});

Caches::clear(
    static function (CacheUsage $usage): void {
        echo "Freed {$usage->freedBytes} bytes";
    },
);
```

`Caches::clear()` removes decoded image files, ordinary media and temporary
incoming-share files. Media explicitly marked `pinOffline()` is preserved by
default. Pass `preserveOffline: false` only after explicit user confirmation to
remove downloaded offline content as well.

## Runtime behavior

- Memory and disk hits avoid a duplicate network request.
- Concurrent requests for the same identity share one download.
- Files are written to a temporary path and activated only after validation.
- Expired and oversized entries are evicted by least-recent use.
- Pinned files survive normal eviction and are intended for explicit offline content.
- Recycled native views use source generations, so late work cannot render into a
  component that now represents another item.
- Android retries bounded transient failures while an image view stays attached.
  A completed request is reused only if it still owns a drawable, so an image
  cannot remain blank until its virtualized cell is recycled.
- Android validates redirect count, origin-sensitive headers, response type, decoded
  image bounds, byte limits, and checksums.
- iOS stores cache files under the application cache sandbox and uses ImageIO
  downsampling to avoid decoding a full-resolution image when a smaller target is
  requested.

For adaptive HLS/DASH streams, the operating-system player may maintain its own segment
cache. Pam's file cache is intended for ordinary remote files; offline adaptive streams
still require a platform download manifest/license workflow.
