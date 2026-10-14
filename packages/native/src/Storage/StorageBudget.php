<?php

declare(strict_types=1);

namespace Pam\Native\Storage;

use function array_slice;
use function count;
use function is_string;
use function strlen;

/**
 * Keeps JSON written to the native key-value Storage under its 256 KiB value
 * limit, which otherwise rejects the whole write. Lists keep their head
 * (store them newest first), maps keep their most recent entries, and an
 * oversized object becomes '' (no cache) instead of a failed write.
 *
 *     Storage::set('feed.page', StorageBudget::encodeList($posts, limit: 48));
 *     Storage::set('profile.me', StorageBudget::encodeObject($profile));
 */
final class StorageBudget
{
    /** Encoded bytes kept safely under Storage::MAX_VALUE_BYTES. */
    public const MAX_BYTES = 240_000;

    private const FLAGS = JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES;

    private function __construct()
    {
    }

    /**
     * The first $limit items whose JSON fits $maxBytes.
     *
     * @template T
     * @param list<T> $items
     * @return list<T>
     */
    public static function trimList(array $items, int $limit = PHP_INT_MAX, int $maxBytes = self::MAX_BYTES): array
    {
        $items = array_slice($items, 0, max(0, $limit));
        while ($items !== []) {
            $encoded = json_encode($items, JSON_UNESCAPED_SLASHES);
            if (!is_string($encoded)) {
                return [];
            }
            if (strlen($encoded) <= $maxBytes) {
                break;
            }
            // Drop proportionally to the overflow (at least one item).
            $keep = (int) floor(count($items) * $maxBytes / strlen($encoded));
            $items = array_slice($items, 0, max(0, min(count($items) - 1, $keep)));
        }

        return $items;
    }

    /** @param list<mixed> $items */
    public static function encodeList(array $items, int $limit = PHP_INT_MAX, int $maxBytes = self::MAX_BYTES): string
    {
        return json_encode(self::trimList($items, $limit, $maxBytes), self::FLAGS);
    }

    /**
     * A keyed map in insertion order (oldest first): keeps the most recent
     * $limit entries that fit.
     *
     * @param array<array-key, mixed> $map
     */
    public static function encodeMap(array $map, int $limit = PHP_INT_MAX, int $maxBytes = self::MAX_BYTES): string
    {
        if ($map === []) {
            return '{}';
        }
        $entries = [];
        foreach (array_reverse(array_keys($map)) as $key) {
            $entries[] = [$key, $map[$key]];
        }
        $kept = [];
        foreach (array_reverse(self::trimList($entries, $limit, $maxBytes)) as [$key, $value]) {
            $kept[$key] = $value;
        }

        return $kept === [] ? '{}' : json_encode($kept, self::FLAGS);
    }

    /**
     * A single record: '' when it does not fit, so the key is cleared and a
     * stale snapshot is never kept.
     *
     * @param array<array-key, mixed> $value
     */
    public static function encodeObject(array $value, int $maxBytes = self::MAX_BYTES): string
    {
        $encoded = json_encode($value, self::FLAGS);

        return strlen($encoded) <= $maxBytes ? $encoded : '';
    }
}
