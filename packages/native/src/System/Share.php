<?php

declare(strict_types=1);

namespace Pam\Native\System;

use Closure;
use Pam\Native\Internal\Runtime;
use Pam\Native\Internal\Wire;
use Pam\Native\ModuleResultStatus;
use Pam\Native\Modules\NativeModules;
use Pam\Native\NativeOperation;
use InvalidArgumentException;
use RuntimeException;

final class Share
{
    private function __construct()
    {
    }

    public static function text(string $text, ?string $title = null, ?Closure $opened = null): int
    {
        return Runtime::callNative(
            NativeOperation::Share,
            Wire::map([
                'text' => $text,
                'title' => $title ?? '',
            ]),
            static function () use ($opened): void {
                $opened?->__invoke();
            },
        );
    }

    /**
     * Shares private sandbox files through the platform share sheet.
     *
     * Android grants temporary read access through the PAM FileProvider and uses
     * ACTION_SEND for one file or ACTION_SEND_MULTIPLE for several files.
     *
     * @param list<string> $paths Relative private file paths.
     * @param null|Closure(): void $opened
     * @param null|Closure(string): void $failure
     */
    public static function files(
        array $paths,
        ?string $mimeType = null,
        ?string $title = null,
        ?Closure $opened = null,
        ?Closure $failure = null,
    ): int {
        if ($paths === [] || count($paths) > 50 || !array_is_list($paths)) {
            throw new InvalidArgumentException('Share between 1 and 50 private files.');
        }
        $normalized = array_map(
            static fn (mixed $path): string => is_string($path)
                ? Files::privatePath($path)
                : throw new InvalidArgumentException('Shared file paths must be strings.'),
            $paths,
        );
        $mimeType = trim((string) $mimeType);
        if ($mimeType !== '' && preg_match('#^[a-z0-9!\#$&^_.+-]+/[a-z0-9!\#$&^_.+*-]+$#iD', $mimeType) !== 1) {
            throw new InvalidArgumentException('Share MIME type is invalid.');
        }

        return NativeModules::call(
            'files',
            'share',
            [
                'paths' => json_encode($normalized, JSON_THROW_ON_ERROR | JSON_UNESCAPED_SLASHES),
                'mimeType' => $mimeType,
                'title' => $title ?? '',
            ],
            static function ($result) use ($opened, $failure): void {
                if ($result->status === ModuleResultStatus::Failure) {
                    if ($failure !== null) {
                        $failure($result->payload);

                        return;
                    }
                    throw new RuntimeException($result->payload);
                }
                $opened?->__invoke();
            },
        );
    }
}
