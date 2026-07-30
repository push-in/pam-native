<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use JsonException;
use Pam\Native\Internal\Wire;
use Pam\Native\MediaAlbum;
use Pam\Native\MediaAsset;
use Pam\Native\MediaAssetPage;
use Pam\Native\MediaPickerType;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use RuntimeException;

final class MediaLibrary
{
    private function __construct()
    {
    }

    /** @param Closure(MediaAssetPage): void $callback */
    public static function assets(
        MediaPickerType $type,
        Closure $callback,
        int $limit = 80,
        int $offset = 0,
        ?string $albumId = null,
    ): int {
        return self::invoke(
            'assets',
            [
                'albumId' => $albumId ?? '',
                'limit' => max(1, min(200, $limit)),
                'offset' => max(0, $offset),
                'type' => $type->value,
            ],
            static function (array $values) use ($callback, $offset): void {
                $items = array_map(
                    self::asset(...),
                    self::decodeList((string) ($values['items'] ?? '[]')),
                );
                $callback(new MediaAssetPage(
                    items: $items,
                    nextOffset: $offset + count($items),
                    hasMore: (bool) ($values['hasMore'] ?? false),
                ));
            },
        );
    }

    /** @param Closure(list<MediaAlbum>): void $callback */
    public static function albums(
        MediaPickerType $type,
        Closure $callback,
    ): int {
        return self::invoke(
            'albums',
            ['type' => $type->value],
            static fn (array $values): mixed => $callback(array_map(
                static fn (array $item): MediaAlbum => new MediaAlbum(
                    id: (string) ($item['id'] ?? ''),
                    title: (string) ($item['title'] ?? ''),
                    count: max(0, (int) ($item['count'] ?? 0)),
                    coverUri: (string) ($item['coverUri'] ?? ''),
                ),
                self::decodeList((string) ($values['items'] ?? '[]')),
            )),
        );
    }

    /** @param array<string, mixed> $item */
    private static function asset(array $item): MediaAsset
    {
        return new MediaAsset(
            id: (string) ($item['id'] ?? ''),
            uri: (string) ($item['uri'] ?? ''),
            name: (string) ($item['name'] ?? ''),
            mimeType: (string) ($item['mimeType'] ?? 'application/octet-stream'),
            width: max(0, (int) ($item['width'] ?? 0)),
            height: max(0, (int) ($item['height'] ?? 0)),
            durationMs: max(0, (int) ($item['durationMs'] ?? 0)),
            size: max(0, (int) ($item['size'] ?? 0)),
            createdAt: max(0, (int) ($item['createdAt'] ?? 0)),
            modifiedAt: max(0, (int) ($item['modifiedAt'] ?? 0)),
            albumId: (string) ($item['albumId'] ?? ''),
            albumTitle: (string) ($item['albumTitle'] ?? ''),
            favorite: (bool) ($item['favorite'] ?? false),
        );
    }

    /** @return list<array<string, mixed>> */
    private static function decodeList(string $payload): array
    {
        try {
            $items = json_decode($payload, true, flags: JSON_THROW_ON_ERROR);
        } catch (JsonException $error) {
            throw new RuntimeException('Native media-library payload is invalid.', previous: $error);
        }
        if (!is_array($items) || !array_is_list($items)) {
            throw new RuntimeException('Native media-library payload is invalid.');
        }

        return array_values(array_filter($items, 'is_array'));
    }

    private static function invoke(string $method, array $payload, Closure $callback): int
    {
        return NativeModules::call(
            'media-library',
            $method,
            $payload,
            static function ($result) use ($callback): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    throw new RuntimeException($result->payload);
                }
                $callback($result->payload === '' ? [] : Wire::decodeMap($result->payload));
            },
        );
    }
}
