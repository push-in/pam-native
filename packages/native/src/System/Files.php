<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\FileReference;
use Pam\Native\Internal\Wire;
use Pam\Native\MediaPickerType;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use JsonException;
use RuntimeException;

final class Files
{
    private function __construct()
    {
    }

    /** @param Closure(string): void $callback */
    public static function read(string $path, Closure $callback): int
    {
        return self::invoke('read', ['path' => $path], static function (array $values) use ($callback): void {
            $decoded = base64_decode((string) ($values['data'] ?? ''), true);
            if ($decoded === false) {
                throw new RuntimeException('Native file payload is invalid.');
            }
            $callback($decoded);
        });
    }

    public static function write(string $path, string $contents, ?Closure $callback = null): int
    {
        return self::invoke(
            'write',
            ['path' => $path, 'data' => base64_encode($contents)],
            static fn (array $_): mixed => $callback?->__invoke(),
        );
    }

    /** @param Closure(FileReference): void $callback */
    public static function stat(string $path, Closure $callback): int
    {
        return self::invoke(
            'stat',
            ['path' => $path],
            static fn (array $values): mixed => $callback(self::reference($values)),
        );
    }

    /** @param Closure(list<FileReference>): void $callback */
    public static function list(string $directory, Closure $callback): int
    {
        return self::invoke('list', ['path' => $directory], static function (array $values) use ($callback): void {
            $items = json_decode((string) ($values['items'] ?? '[]'), true, flags: JSON_THROW_ON_ERROR);
            if (!is_array($items)) {
                throw new RuntimeException('Native file listing is invalid.');
            }
            $callback(array_map(
                static fn (mixed $item): FileReference => self::reference(is_array($item) ? $item : []),
                $items,
            ));
        });
    }

    public static function delete(string $path, ?Closure $callback = null): int
    {
        return self::invoke(
            'delete',
            ['path' => $path],
            static fn (array $_): mixed => $callback?->__invoke(),
        );
    }

    /** @param Closure(FileReference): void $callback */
    public static function pick(
        MediaPickerType $type,
        Closure $callback,
    ): int {
        return self::invoke(
            'pick',
            ['type' => $type->value],
            static fn (array $values): mixed => $callback(self::reference($values)),
        );
    }

    /**
     * @param Closure(list<FileReference>): void $callback
     */
    public static function pickMany(
        MediaPickerType $type,
        Closure $callback,
        int $limit = 10,
    ): int {
        return self::invoke(
            'pickMany',
            [
                'limit' => max(1, min(50, $limit)),
                'type' => $type->value,
            ],
            static function (array $values) use ($callback): mixed {
                try {
                    $items = json_decode(
                        (string) ($values['items'] ?? '[]'),
                        true,
                        flags: JSON_THROW_ON_ERROR,
                    );
                } catch (JsonException $error) {
                    throw new RuntimeException(
                        'Native multi-file payload is invalid.',
                        previous: $error,
                    );
                }
                if (!is_array($items)) {
                    throw new RuntimeException('Native multi-file payload is invalid.');
                }
                $references = [];
                foreach ($items as $item) {
                    if (is_array($item)) {
                        $references[] = self::reference($item);
                    }
                }
                $callback($references);

                return null;
            },
        );
    }

    /**
     * Copies a content URI granted to the application into the PAM file
     * sandbox so it can be uploaded, edited and retained safely.
     *
     * @param Closure(FileReference): void $callback
     */
    public static function importUri(string $uri, Closure $callback): int
    {
        if (!str_starts_with($uri, 'content://')) {
            throw new RuntimeException('Only content URIs can be imported.');
        }

        return self::invoke(
            'importUri',
            ['uri' => $uri],
            static fn (array $values): mixed => $callback(self::reference($values)),
        );
    }

    private static function reference(array $values): FileReference
    {
        return new FileReference(
            path: (string) ($values['path'] ?? ''),
            name: (string) ($values['name'] ?? ''),
            mimeType: (string) ($values['mimeType'] ?? 'application/octet-stream'),
            size: (int) ($values['size'] ?? 0),
        );
    }

    private static function invoke(string $method, array $payload, Closure $callback): int
    {
        return NativeModules::call('files', $method, $payload, static function ($result) use ($callback): void {
            if ($result->status === ModuleResultStatus::Failure) {
                throw new RuntimeException($result->payload);
            }
            $callback($result->payload === '' ? [] : Wire::decodeMap($result->payload));
        });
    }
}
