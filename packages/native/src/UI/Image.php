<?php

declare(strict_types=1);

namespace Pam\Native\UI;

use Closure;
use InvalidArgumentException;
use Pam\Native\Element;
use Pam\Native\ImagePrefetchResult;
use Pam\Native\Internal\Wire;
use Pam\Native\MediaPriority;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\NodeKind;
use Pam\Native\PropKey;
use Pam\Native\UI\Concerns\HasImageBehavior;

use function count;
use function in_array;
use function is_string;
use function strlen;

final class Image extends Element
{
    use HasImageBehavior;

    public static function make(string $source): self
    {
        return (new self(NodeKind::Image))->withProperty(PropKey::Source, $source);
    }

    /**
     * Downloads remote images into the shared image disk cache ahead of
     * rendering. Pass the same headers/cache keys the Image will use.
     *
     * @param list<string> $urls
     * @param array<string, string> $headers
     * @param array<string, string> $cacheKeys Optional url => stable cache key map.
     * @param null|Closure(ImagePrefetchResult): void $callback
     */
    public static function prefetch(
        array $urls,
        MediaPriority $priority = MediaPriority::Prefetch,
        array $headers = [],
        ?Closure $callback = null,
        array $cacheKeys = [],
    ): int {
        $urls = array_values(array_unique(array_filter(
            $urls,
            static fn (mixed $url): bool => is_string($url) && $url !== '',
        )));
        if ($urls === [] || count($urls) > 100) {
            throw new InvalidArgumentException('Prefetch between 1 and 100 image URLs.');
        }
        foreach ($urls as $url) {
            $scheme = strtolower((string) parse_url($url, PHP_URL_SCHEME));
            if (strlen($url) > 8_192 || !in_array($scheme, ['https', 'http'], true)) {
                throw new InvalidArgumentException('Only remote HTTP(S) images can be prefetched.');
            }
        }
        $packedHeaders = (new self(NodeKind::Image))->headers($headers)->properties()[PropKey::ImageRequestHeaders->value] ?? '';
        $keys = array_map(static fn (string $url): string => (string) ($cacheKeys[$url] ?? ''), $urls);

        return NativeModules::call(
            'image',
            'prefetch',
            [
                'urls' => json_encode($urls, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES),
                'priority' => $priority->value,
                'headers' => (string) $packedHeaders,
                'cacheKeys' => json_encode($keys, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES),
            ],
            static function ($result) use ($callback, $urls): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    $callback?->__invoke(new ImagePrefetchResult(0, count($urls), 0));

                    return;
                }
                $values = Wire::decodeMap($result->payload);
                $callback?->__invoke(new ImagePrefetchResult(
                    succeeded: max(0, (int) ($values['succeeded'] ?? 0)),
                    failed: max(0, (int) ($values['failed'] ?? 0)),
                    bytes: max(0, (int) ($values['bytes'] ?? 0)),
                ));
            },
        );
    }
}
